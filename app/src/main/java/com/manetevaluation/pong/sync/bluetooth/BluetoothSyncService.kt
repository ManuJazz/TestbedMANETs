package com.manetevaluation.pong.sync.bluetooth

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import com.manetevaluation.pong.sync.*
import com.manetevaluation.pong.views.SettingsActivity
import com.manetevaluation.pong.R
import net.vidageek.mirror.dsl.Mirror
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.math.pow
import kotlin.random.Random

class BluetoothSyncService : Service() {

    private var started = false
    private var serviceUuidAndAddress: UUID? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothClassicServer: Thread? = null
    private var openConnections: ConcurrentHashMap<String, Boolean>? = null
    private var latestRSSI : ConcurrentHashMap<String, Int>? = null
    private var disconnections : ConcurrentHashMap<String, Int>? = null
    private var bitsTransmitedSet : ConcurrentHashMap<String, QoSMetrics>? = null
    private var initialScanning : Boolean = true
    lateinit var absolutePath : File
    lateinit var innerMacAddress : String

    override fun onBind(intent: Intent): IBinder? {
        // TODO: Return the communication channel to the service.
        throw UnsupportedOperationException("Not yet implemented")
    }

    enum class CanStartResult {
        CAN_START,
        BLUETOOTH_OR_BLE_UNSUPPORTED,
        BLUETOOTH_OFF,
        BLUETOOTH_ADDRESS_UNAVAILABLE
    }

    private fun buildAdvertiseData(): AdvertiseData {
        val builder = AdvertiseData.Builder()

        // We are including this device's physical MAC address in the advertisement to enable higher bandwidth pair-free communication over Bluetooth Classic sockets.
        // While our communications will always be anonymous by design, this still has privacy implications:
        // If an attacker manages to associate an address with a person, they will be able to determine if that person is nearby as long as the app is installed on that phone.
        builder.addServiceUuid(ParcelUuid(serviceUuidAndAddress))
        // TODO: Include some portion of the sync bit string/Bloom filter from the database
        builder.setIncludeDeviceName(false)
        return builder.build()
    }

    private fun buildAdvertiseSettings(): AdvertiseSettings {
        val builder = AdvertiseSettings.Builder()
        builder.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
        builder.setTimeout(0) // Advertise as long as Bluetooth is on, blatantly ignoring Google's advice.
        builder.setConnectable(false)
        return builder.build()
    }

    private fun buildScanSettings(): ScanSettings {
        val builder = ScanSettings.Builder()
        builder.setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)

        if (Build.VERSION.SDK_INT >= 23 /* Marshmallow */) {
            builder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            builder.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            builder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        }

        return builder.build()
    }

    private fun startBluetoothLeDiscovery(startId: Int) {
        absolutePath = this.filesDir
        StreamSync.setAbsolutePath(this.absolutePath)
        bluetoothLeAdvertiser!!.startAdvertising(buildAdvertiseSettings(), buildAdvertiseData(),
                object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                        super.onStartSuccess(settingsInEffect)
                        Log.d(TAG, "BLE advertise started")
                        val intent = Intent("com.example.ACTION_NEW_DATA")
                        intent.putExtra("new_string", "BLE advertise started")
                        sendBroadcast(intent)
                    }

                    override fun onStartFailure(errorCode: Int) {
                        super.onStartFailure(errorCode)
                        Log.e(TAG, "BLE advertise failed to start: error $errorCode")
                        val intent = Intent("com.example.ACTION_NEW_DATA")
                        intent.putExtra("new_string", "BLE advertise failed to start: error $errorCode")
                        sendBroadcast(intent)
                        stopSelf(startId)
                        // TODO: Is it safe to restart the advertisement?
                    }
                })

        // Scan filters on service UUIDs were completely broken on the devices I tested (fully updated Google Pixel and Moto G4 Play as of March 2017)
        // https://stackoverflow.com/questions/29664316/bluetooth-le-scan-filter-not-working
        // TODO: Check if that's supported using bluetoothAdapter.isOffloadedFilteringSupported/isOffloadedScanBatchingSupported
        // https://stackoverflow.com/questions/26482611/chipsets-devices-supporting-android-5-ble-peripheral-mode
        bluetoothLeScanner!!.startScan(null, buildScanSettings(),
                object : ScanCallback() {
                    override fun onScanFailed(errorCode: Int) {
                        super.onScanFailed(errorCode)
                        Log.e(TAG, "BLE scan failed to start: error $errorCode")
                        stopSelf(startId)
                        // TODO: Is it safe to restart the scan?
                    }

                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        super.onScanResult(callbackType, result)

                        super.onScanResult(callbackType, result)

                        if (result.scanRecord == null || result.scanRecord!!.serviceUuids == null)
                            return

                        for (uuid in result.scanRecord!!.serviceUuids) {
                            Thread{manageNewConnection(uuid, result)}.start()
                        }
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                        super.onBatchScanResults(results)
                        if (results != null) {
                            Log.d(TAG, "Checking connections... [${results.size}")
                            for(scans : ScanResult in results){
                                Thread {
                                    for(uuid in scans.device.uuids){
                                        if (manageNewConnection(uuid, scans)){
                                            latestRSSI?.set(scans.device.address, scans.rssi)
                                            Log.d(TAG, "RSSI detected... [${scans.device.address}] ${scans.rssi}")
                                        }
                                    }
                                }.start()
                            }
                        }
                    }
                }
        )/*filters*/
    }

    private fun manageNewConnection(uuid: ParcelUuid, result: ScanResult) : Boolean {
        Log.d(TAG, "        [${macAddressFromLong(uuid.uuid.mostSignificantBits)}:${macAddressFromLong(uuid.uuid.leastSignificantBits)}]")
        if (!matchesServiceUuid(uuid.uuid)){
            return false
        }

        latestRSSI?.set(result.device.address, result.rssi)
        Log.d(TAG, "BLE scanner found supported device")

        val remoteDeviceMacAddress = macAddressFromLong(uuid.uuid.leastSignificantBits)
        Log.d(TAG, "        [$remoteDeviceMacAddress]")
        val remoteDevice = bluetoothAdapter!!.getRemoteDevice(remoteDeviceMacAddress)

        if(!checkAlreadyRegistered(remoteDevice) && remoteDeviceMacAddress!=innerMacAddress){
            addNewNode(remoteDevice)
        }
        BluetoothClassicClient(remoteDevice, uuid.uuid).start()
        return true
    }

    private fun stopBluetoothLeDiscovery() {
        if (!bluetoothAdapter!!.isEnabled)
            return

        if (bluetoothLeAdvertiser != null) {
            bluetoothLeAdvertiser!!.stopAdvertising(object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) {
                    super.onStartFailure(errorCode)
                    Log.e(TAG, "BLE advertise failed to stop: error $errorCode")
                }
            })
        }

        if (bluetoothLeScanner != null) {
            bluetoothLeScanner!!.stopScan(object : ScanCallback() {
                override fun onScanFailed(errorCode: Int) {
                    super.onScanFailed(errorCode)
                    Log.e(TAG, "BLE scan failed to stop: error $errorCode")
                }
            })
        }
    }

    private inner class BluetoothClassicServer(uuid: UUID) : Thread() {
        private var serverSocket: BluetoothServerSocket? = null

        init {
            try {
                serverSocket = bluetoothAdapter!!.listenUsingInsecureRfcommWithServiceRecord(TAG, uuid)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to set up Bluetooth Classic connection as a server", e)
            }

        }

        override fun run() {
            var socket: BluetoothSocket? = null
            var macAddress: String? = null
            while (bluetoothAdapter!!.isEnabled && started) {
                Log.e(TAG, "WAITING INCOMING PETITIONS")
                val intent = Intent("com.example.ACTION_NEW_DATA")
                intent.putExtra("new_string", "WAITING INCOMING PETITIONS")
                sendBroadcast(intent)
                try {
                    socket = serverSocket!!.accept()
                    AttendIncommingConnection(socket).start()
                }catch (connectException: IOException){
                    Log.e(TAG, "Failed to start a Bluetooth Classic connection as a server", connectException)
                    //check disconnection
                    try {
                        socket?.close()
                    } catch (closeException: IOException) {
                        Log.e(TAG, "Failed to close a Bluetooth Classic connection as a server", closeException)
                    }
                }
            }
        }
    }

    private inner class AttendIncommingConnection(socket: BluetoothSocket) : Thread(){
        var socket: BluetoothSocket = socket
        var macAddress = socket!!.remoteDevice.address
        lateinit var initialDate : Date
        lateinit var endDate : Date
        var encounterlength : Long = 0
        var disconnectionCounter : Int = 0
        lateinit var qosMetric : QoSMetrics
        var throughtput : Long =0
        var distance : Double = 0.0

        override fun run(){
            try {
                initialDate = Date()
                // This will block until there is a connection
                Log.d(TAG, "Bluetooth Classic server is listening for a client")
                val intent = Intent("com.example.ACTION_NEW_DATA")
                intent.putExtra("new_string", "Bluetooth Classic server is listening for a client")
                sendBroadcast(intent)
                macAddress = socket!!.remoteDevice.address
                NewContactWriter(absolutePath, Date(), innerMacAddress, macAddress).start()
                bitsTransmitedSet?.set(macAddress, QoSMetrics(1,1,initialDate))
                if (!openConnections!!.containsKey(macAddress)) {
                    openConnections!![macAddress!!] = true
                    // New contact
                    StreamSync.bidirectionalSync(socket.inputStream, socket.outputStream, macAddress, bitsTransmitedSet!![macAddress])
                }
                socket.close()
            } catch (connectException: IOException) {
                Log.e(TAG, "Failed to start a Bluetooth Classic connection as a server", connectException)
                //check disconnection
                disconnectionCounter = if(disconnections!!.keys.contains(macAddress)){
                    disconnections?.getValue(macAddress)?.plus(1)!!
                }else{
                    1;
                }
                disconnections?.set(macAddress, disconnectionCounter)
                try {
                    socket.close()
                } catch (closeException: IOException) {
                    Log.e(TAG, "Failed to close a Bluetooth Classic connection as a server", closeException)
                }
            }finally {
                endDate = Date()
                encounterlength = endDate.time-initialDate.time
                Log.d(TAG, "Bluetooth Classic server successfully closed connection. Lasted: $encounterlength")
                qosMetric = bitsTransmitedSet?.get(macAddress)!!
                if(qosMetric!=null){
                    throughtput = if((qosMetric._endTime.time-qosMetric._initTime.time).compareTo(0)!=0){
                        (qosMetric._bitsSent/(qosMetric._endTime.time-qosMetric._initTime.time))
                    }else{
                        0
                    }
                    distance = if(latestRSSI?.keys?.contains(macAddress) == true){
                        10.0.pow((((26 + latestRSSI?.get(macAddress)!!) / 20).toDouble()))
                    }else{
                        10.0
                    }
                    NewDetailedContactWriter(absolutePath, initialDate, innerMacAddress, macAddress,
                        latestRSSI?.get(macAddress), throughtput,
                        ((qosMetric._bitsSent-qosMetric._bitsTransmited)/qosMetric._bitsTransmited), qosMetric._endTime.time-qosMetric._initTime.time,
                        distance,
                        if(disconnections!!.keys.contains(macAddress)) disconnections!!.getValue(macAddress) else 0,
                        qosMetric._success
                    ).start()
                }
                NewDisconnectionWriter(absolutePath, Date(), innerMacAddress, macAddress, encounterlength).start()
                if (macAddress != null)
                    openConnections!!.remove(macAddress)
            }
        }
    }

    private inner class BluetoothClassicClient(device: BluetoothDevice, uuid: UUID) : Thread() {
        internal var socket: BluetoothSocket? = null
        internal var macAddress: String
        var uuid : UUID = uuid
        lateinit var initialDate : Date
        lateinit var endDate : Date
        lateinit var qosMetric : QoSMetrics
        var encounterlength : Long = 0
        var disconnectionCounter : Int = 0
        var throughtput : Long = 0
        var distance : Double = 0.0
        init {
            macAddress = device.address
            try {
                Log.d(TAG, "Lets connect to        [${macAddressFromLong(uuid.mostSignificantBits)}:${macAddressFromLong(uuid.leastSignificantBits)}]")
                val intent = Intent("com.example.ACTION_NEW_DATA")
                intent.putExtra("new_string", "Lets connect to ${macAddressFromLong(uuid.leastSignificantBits)}")
                sendBroadcast(intent)
                socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
            } catch (connectException: IOException) {
                Log.e(TAG, "Failed to set up a Bluetooth Classic connection as a client", connectException)
                val intent = Intent("com.example.ACTION_NEW_DATA")
                intent.putExtra("new_string", "Failed to set up a Bluetooth Classic connection as a client")
                sendBroadcast(intent)
                //NewDisconnectionWriter(absolutePath, Date(), innerMacAddress, macAddress).start()
            }

        }

        override fun run() {
            // TODO: This should be done with a counter instead (with AtomicInteger)
            if (openConnections!!.containsKey(macAddress))
                return

            openConnections!![macAddress!!] = true
            try {
                // This will block until there is a connection
                Log.d(TAG, "Bluetooth Classic client is attempting to connect to a server")
                Log.d(TAG, "Lets go        [${macAddressFromLong(uuid.mostSignificantBits)}:${macAddressFromLong(uuid.leastSignificantBits)}]")
                val intent = Intent("com.example.ACTION_NEW_DATA")
                intent.putExtra("new_string", "Bluetooth Classic client is attempting to connect to a server")
                sendBroadcast(intent)
                //NewContactWriter(absolutePath, Date(), innerMacAddress, macAddress).start()
                initialDate = Date()
                bitsTransmitedSet?.set(macAddress, QoSMetrics(1,1,initialDate))
                socket!!.connect()
                StreamSync.bidirectionalSync(socket!!.inputStream, socket!!.outputStream, macAddress!!,
                    bitsTransmitedSet!![macAddress]
                )
                socket!!.close()
            } catch (connectException: IOException) {
                disconnectionCounter = if(disconnections!!.keys.contains(macAddress)){
                    disconnections?.getValue(macAddress)?.plus(1)!!
                }else{
                    1;
                }
                disconnections?.set(macAddress, disconnectionCounter)
                Log.e(TAG, "Failed to start a Bluetooth Classic connection as a client", connectException)
                try {
                    socket!!.close()
                } catch (closeException: IOException) {
                    Log.e(TAG, "Failed to close a Bluetooth Classic connection as a client", closeException)
                }
            }finally{
                endDate = Date()
                encounterlength = endDate.time-initialDate.time
                Log.d(TAG, "Bluetooth Classic client successfully closed. Lasted: $encounterlength")
                NewDisconnectionWriter(absolutePath, Date(), innerMacAddress, macAddress, encounterlength).start()
                openConnections!!.remove(macAddress!!)
                qosMetric = bitsTransmitedSet?.get(macAddress)!!
                if(qosMetric!=null){
                    throughtput = if((qosMetric._endTime.time-qosMetric._initTime.time).compareTo(0)!=0){
                        (qosMetric._bitsSent/(qosMetric._endTime.time-qosMetric._initTime.time))
                    }else{
                        0
                    }
                    distance = if(latestRSSI?.keys?.contains(macAddress) == true){
                        10.0.pow((((26 + latestRSSI?.get(macAddress)!!) / 20).toDouble()))
                    }else{
                        10.0
                    }
                    NewDetailedContactWriter(absolutePath, initialDate, innerMacAddress, macAddress,
                        latestRSSI?.get(macAddress), throughtput,
                        ((qosMetric._bitsSent-qosMetric._bitsTransmited)/qosMetric._bitsTransmited),
                        qosMetric._endTime.time-qosMetric._initTime.time,
                        distance,
                        if(disconnections!!.keys.contains(macAddress)) disconnections!!.getValue(macAddress) else 0,
                        qosMetric._success
                    ).start()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (canStart(this) != CanStartResult.CAN_START) {
            Log.e(TAG, "Trying to start the service even though Bluetooth is off or BLE is unsupported")
            stopSelf(startId)
            return Service.START_NOT_STICKY
        }

        if (started) {
            Log.d(TAG, "Started again")
            return Service.START_STICKY
        }

        bluetoothAdapter = getBluetoothAdapter(this)

        // First half identifies that the advertisement is for Noise.
        // Second half is the MAC address of this device's Bluetooth adapter so that clients know how to connect to it.
        // These are not listed separately in the advertisement because a UUID is 16 bytes and ads are limited to 31 bytes.
        val macAddress = getBluetoothAdapterAddress(bluetoothAdapter!!, this)
        if (macAddress == null) {
            Log.e(TAG, "Unable to get this device's Bluetooth MAC address")
            stopSelf(startId)
            return Service.START_NOT_STICKY
        }
        this.innerMacAddress = macAddress
        val uuid = UUID(SERVICE_UUID_HALF.mostSignificantBits, longFromMacAddress(macAddress))
        serviceUuidAndAddress = uuid

        bluetoothLeAdvertiser = bluetoothAdapter!!.bluetoothLeAdvertiser
        bluetoothLeScanner = bluetoothAdapter!!.bluetoothLeScanner
        startBluetoothLeDiscovery(startId)

        started = true
        bluetoothClassicServer = BluetoothClassicServer(uuid)
        bluetoothClassicServer!!.start()

        openConnections = ConcurrentHashMap()
        latestRSSI = ConcurrentHashMap()
        disconnections = ConcurrentHashMap()
        bitsTransmitedSet = ConcurrentHashMap()

        Log.d(TAG, "Started")
        Toast.makeText(this, R.string.bluetooth_sync_started, Toast.LENGTH_LONG).show()
        return Service.START_STICKY
    }

    override fun onDestroy() {
        started = false

        stopBluetoothLeDiscovery()

        // TODO: Verify that this actually stops the thread
        bluetoothClassicServer!!.interrupt()

        // TODO: Stop all BluetoothClassicClient threads

        Toast.makeText(this, R.string.bluetooth_sync_stopped, Toast.LENGTH_LONG).show()
        Log.d(TAG, "Stopped")
        super.onDestroy()
    }

    companion object {
        private lateinit var address: String
        private var nodeNetwork : List<BluetoothDevice> = ArrayList()
        val TAG = "BluetoothSyncService"
        val SERVICE_UUID_HALF = UUID.fromString("5ac825f4-6084-42a6-0000-000000000000")

        private val FAKE_MAC_ADDRESS = "02:00:00:00:00:00"
        private val MAC_PATTERN = Pattern.compile("\\w\\w:\\w\\w:\\w\\w:\\w\\w:\\w\\w:\\w\\w")

        fun getMacAddress() : String{
            return address
        }

        fun canStart(context: Context): CanStartResult {
            val packageManager = context.packageManager
            val bluetoothAdapter = getBluetoothAdapter(context)
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) || !packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                return CanStartResult.BLUETOOTH_OR_BLE_UNSUPPORTED
            } else if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                return CanStartResult.BLUETOOTH_OFF
            } else if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
                return CanStartResult.BLUETOOTH_OR_BLE_UNSUPPORTED
            } else if (getBluetoothAdapterAddress(bluetoothAdapter, context) == null) {
                return CanStartResult.BLUETOOTH_ADDRESS_UNAVAILABLE
            }

            return CanStartResult.CAN_START
        }

        fun startOrPromptBluetooth(context: Context) {
            when (canStart(context)) {
                BluetoothSyncService.CanStartResult.CAN_START -> {
                    Log.d(TAG, "Starting BLE sync service")
                    context.startService(Intent(context, BluetoothSyncService::class.java))
                }
                BluetoothSyncService.CanStartResult.BLUETOOTH_OR_BLE_UNSUPPORTED -> {
                    Log.d(TAG, "BLE not supported, not starting BLE sync service")
                    Toast.makeText(context, R.string.bluetooth_not_supported, Toast.LENGTH_LONG).show()
                }
                BluetoothSyncService.CanStartResult.BLUETOOTH_OFF -> {
                    Log.d(TAG, "BLE supported but Bluetooth is off; will prompt for Bluetooth and start once it's on")
                    Toast.makeText(context, R.string.bluetooth_ask_enable, Toast.LENGTH_LONG).show()
                    context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
                BluetoothSyncService.CanStartResult.BLUETOOTH_ADDRESS_UNAVAILABLE -> {
                    Log.d(TAG, "BLE supported but MAC address is unavailable; will prompt for address and start once it's available")
                    Toast.makeText(context, R.string.bluetooth_ask_address, Toast.LENGTH_LONG).show()
                }
            }// BluetoothSyncServiceManager will start this service once Bluetooth is on.
            // TODO: Open the app's settings? Maybe getting the address should be part of onboarding UI
            // BluetoothSyncServiceManager will start this (re)start this service when the address changes.
        }

        private fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    ?: return null
            return bluetoothManager.adapter
        }

        private fun getBluetoothAdapterAddress(bluetoothAdapter: BluetoothAdapter, context: Context): String? {
            @SuppressLint("HardwareIds") // Pair-free peer-to-peer communication should qualify as an "advanced telephony use case".
            address = bluetoothAdapter.address

            if (address == FAKE_MAC_ADDRESS && Build.VERSION.SDK_INT < 26 /* Oreo */) {
                Log.w(TAG, "bluetoothAdapter.getAddress() did not return the physical address")

                // HACK HACK HACK: getAddress is intentionally broken (but not deprecated?!) on Marshmallow and up:
                //   * https://developer.android.com/about/versions/marshmallow/android-6.0-changes.html#behavior-notifications
                //   * https://code.google.com/p/android/issues/detail?id=197718
                // However, we need it to establish pair-free Bluetooth Classic connections:
                //   * All BLE advertisements include a MAC address, but Android broadcasts a temporary, randomly-generated address.
                //   * Currently, it is only possible to listen for connections using the device's physical address.
                // So we use reflection to get it anyway: http://stackoverflow.com/a/35984808
                // This hack won't be necessary if getAddress is ever fixed (unlikely) or (preferably) we can listen using an arbitrary address.

                val bluetoothManagerService = Mirror().on(bluetoothAdapter).get().field("mService")
                if (bluetoothManagerService == null) {
                    Log.w(TAG, "Couldn't retrieve bluetoothAdapter.mService using reflection")
                    return null
                }

                val internalAddress = Mirror().on(bluetoothManagerService).invoke().method("getAddress").withoutArgs()
                if (internalAddress == null || internalAddress !is String) {
                    Log.w(TAG, "Couldn't call bluetoothAdapter.mService.getAddress() using reflection")
                    return null
                }

                address = internalAddress
            }

            // On Oreo and above, Android will throw a SecurityException if we try to get the MAC address with reflection
            // https://android-developers.googleblog.com/2017/04/changes-to-device-identifiers-in.html
            // https://stackoverflow.com/a/35984808/702467
            if (address == FAKE_MAC_ADDRESS) {
                Log.w(TAG, "Android is actively blocking requests to get the MAC address")

                val preferences = PreferenceManager.getDefaultSharedPreferences(context)
                address = preferences.getString(SettingsActivity.KEY_BLUETOOTH_MAC, "")!!.toUpperCase()
                if (!MAC_PATTERN.matcher(address).matches())
                    return null
            }

            return address
        }

        private fun matchesServiceUuid(uuid: UUID): Boolean {
            return SERVICE_UUID_HALF.mostSignificantBits == uuid.mostSignificantBits
        }

        fun longFromMacAddress(macAddress: String): Long {
            return java.lang.Long.parseLong(macAddress.replace(":".toRegex(), ""), 16)
        }

        fun macAddressFromLong(macAddressLong: Long): String {
            return String.format("%02x:%02x:%02x:%02x:%02x:%02x",
                    (macAddressLong shr 40).toByte(),
                    (macAddressLong shr 32).toByte(),
                    (macAddressLong shr 24).toByte(),
                    (macAddressLong shr 16).toByte(),
                    (macAddressLong shr 8).toByte(),
                    macAddressLong.toByte()).toUpperCase()
        }

        fun addNewNode(device : BluetoothDevice){
            this.nodeNetwork= this.nodeNetwork + device
        }

        fun checkAlreadyRegistered (device : BluetoothDevice) : Boolean{
            return this.nodeNetwork.contains(device)
        }

        fun getRandomNode() : Long{
            val destinationAddress : Long
            if(nodeNetwork.isNotEmpty()) {
                destinationAddress = longFromMacAddress(
                    nodeNetwork[Random.nextInt(
                        0,
                        this.nodeNetwork.size
                    )].address
                )
            }else{
                destinationAddress = longFromMacAddress(address)
            }
            return destinationAddress
        }
    }

    class NewContactWriter(absolutePath : File, _timestamp: Date, _sourceNode : String, _connectedNode : String) : Thread() {
        var absolutePath = absolutePath
        var _timestamp = _timestamp
        var _sourceNode = _sourceNode
        var _connectedNode = _connectedNode
        override fun run(){
            Log.w("NewContactWriter", "New connection registered")
            LocalReport.addNewContact(absolutePath, Connection(_timestamp, _sourceNode, _connectedNode,0))
        }
    }

    class NewDetailedContactWriter(
        absolutePath: File, _timestamp: Date, _sourceNode: String,
        _connectedNode: String, _rssi: Int?, _throughput: Long, _BER: Long, _latency: Long, _distance: Double, _disconnections : Int,
        _success : Boolean
    ) : Thread(){
        var absolutePath = absolutePath
        var timestamp = _timestamp
        var sourceNode = _sourceNode
        var destinationNode = _connectedNode
        var rssi = _rssi
        var throughput = _throughput
        var BER = _BER
        var latency = _latency
        var distance = _distance
        var disconnections = _disconnections
        var success = _success
        override fun run(){
            Log.w("NewDetailedContact", "New connection registered")
            LocalReport.addNewDetailedConnection(absolutePath, DetailedConnection(timestamp, sourceNode, destinationNode,rssi, throughput, BER, latency, distance, disconnections, success))
        }
    }

    class NewDisconnectionWriter(
        var absolutePath: File,
        var _timestamp: Date,
        var _sourceNode: String,
        var _connectedNode: String,
        var _encounterlength: Long
    ) : Thread() {
        override fun run(){
            Log.w("NewDisconnectionWriter", "New disconnection registered")
            LocalReport.addNewDisconnection(absolutePath, Connection(_timestamp, _sourceNode, _connectedNode, _encounterlength))
        }
    }

}
