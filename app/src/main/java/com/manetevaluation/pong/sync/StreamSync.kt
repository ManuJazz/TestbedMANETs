package com.manetevaluation.pong.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import com.manetevaluation.pong.storage.BloomFilter
import com.manetevaluation.pong.storage.UnknownMessage
import com.manetevaluation.pong.sync.LocalReport.Companion.addNewDeliveredMessage
import com.manetevaluation.pong.sync.LocalReport.Companion.addNewForwardedMessage
import com.manetevaluation.pong.sync.LocalReport.Companion.addNewReceivedMessage
import com.manetevaluation.pong.sync.bluetooth.BluetoothSyncService
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.FlowableEmitter
import okio.BufferedSink
import okio.BufferedSource
import okio.Okio
import org.threeten.bp.LocalDateTime
import org.threeten.bp.format.DateTimeFormatter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

object StreamSync {
    val TAG = "StreamSync"

    private val PROTOCOL_NAME = "Noise0"
    private val DEFAULT_CHARSET = Charset.forName("US-ASCII")
    private var nConnections : Int = 0
    private var nMessagesReceived : Int = 0
    private lateinit var absolutePath : File
    private lateinit var context : Context
    lateinit var _qoSMetrics : QoSMetrics

    fun setAbsolutePath(path : File){
        this.absolutePath = path
    }

    fun setLongTimeContext(_context : Context){
        context = _context
    }

    fun bidirectionalSync(inputStream: InputStream, outputStream: OutputStream, connectionAddress: String, qoSMetrics: QoSMetrics?) {
        Log.d(TAG, "Starting sync")

        var intent = Intent("com.example.ACTION_NEW_DATA")
        intent.putExtra("new_string", "Starting sync with $connectionAddress")
        context.sendBroadcast(intent)
        // TODO: Set timeouts

        if (qoSMetrics != null) {
            this._qoSMetrics = qoSMetrics
        }

        val source = Okio.buffer(Okio.source(inputStream))
        val sink = Okio.buffer(Okio.sink(outputStream))
        val ioExecutors = Executors.newFixedThreadPool(2) // Separate threads for send and receive

        val handshakeFutures = handshakeAsync(source, sink, ioExecutors)

        try {
            handshakeFutures.get()
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed", e)

            return
        }

        Log.d(TAG, "Connected to a peer")
        nConnections++;
        Log.d(TAG, "                nConnections: $nConnections")
        intent = Intent("com.example.ACTION_NEW_DATA")
        intent.putExtra("new_string", "Connected to $connectionAddress")
        context.sendBroadcast(intent)

        val myMessageVector = BloomFilter.messageVectorAsync.blockingGet()
        val messageVectorFutures = exchangeMessageVectorsAsync(myMessageVector, source, sink, ioExecutors)

        val theirMessageVector: BitSet?
        try {
            theirMessageVector = messageVectorFutures.get()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to exchange message vectors", e)
            return
        }

        // TODO: Include a subset of the message vector in the broadcast and verify that theirMessageVector matches

        Log.d(TAG, "Exchanged message vectors")
        intent = Intent("com.example.ACTION_NEW_DATA")
        intent.putExtra("new_string", "Vectors shared to $connectionAddress")
        context.sendBroadcast(intent)
        ioExecutors.shutdown()

        val vectorDifference = myMessageVector.clone() as BitSet
        vectorDifference.andNot(theirMessageVector)

        // TODO: Is this I/O as parallel as you think it is? Look into explicitly using separate threads for these
        val myMessages = BloomFilter.getMatchingMessages(vectorDifference)
        sendMessagesAsync(myMessages, sink, connectionAddress)

        val theirMessages = receiveMessagesAsync(source, connectionAddress)
        theirMessages.subscribe(
                { message: UnknownMessage ->
                    message.saveAsync().subscribe()
                    nMessagesReceived++
                },
                { e: Throwable -> Log.e(TAG, "Error receiving messages", e) })

        // Wait until both complete so that we don't prematurely close the connection
        Log.d(TAG, "Sync completed")
        Log.d(TAG, "                nMessagesReceived: $nMessagesReceived")
        intent = Intent("com.example.ACTION_NEW_DATA")
        intent.putExtra("new_string", "Sync with $connectionAddress done")
        context.sendBroadcast(intent)
    }

    private enum class Messages private constructor(internal val value: Byte) {
        MESSAGE_VECTOR(1.toByte()),
        MESSAGE(2.toByte()),
        END(3.toByte())
    }

    internal class IOFutures<T> {
        var sender: Future<Void>? = null
        var receiver: Future<T>? = null

        @Throws(InterruptedException::class, ExecutionException::class)
        fun get(): T? {
            if (sender != null)
                sender!!.get()

            return if (receiver != null)
                receiver!!.get()
            else
                null
        }
    }

    internal fun handshakeAsync(source: BufferedSource, sink: BufferedSink, ioExecutors: ExecutorService): IOFutures<String> {
        if (PROTOCOL_NAME.length > java.lang.Byte.MAX_VALUE)
            Log.wtf(TAG, "Protocol name is too long")

        val futures = IOFutures<String>()

        futures.sender = ioExecutors.submit<Void> {
            sink.writeByte(PROTOCOL_NAME.length)
            sink.writeString(PROTOCOL_NAME, DEFAULT_CHARSET)
            sink.flush()
            null
        }

        futures.receiver = ioExecutors.submit<String> {
            val protocolNameLength = source.readByte()
            val protocolName = source.readString(protocolNameLength.toLong(), DEFAULT_CHARSET)
            if (protocolName != PROTOCOL_NAME)
                throw IOException("Protocol \"$protocolName\" not supported")
            protocolName
        }

        return futures
    }

    internal fun exchangeMessageVectorsAsync(
            myMessageVector: BitSet, source: BufferedSource, sink: BufferedSink, ioExecutors: ExecutorService): IOFutures<BitSet> {
        val futures = IOFutures<BitSet>()

        futures.sender = ioExecutors.submit<Void> {
            sink.writeByte(Messages.MESSAGE_VECTOR.value.toInt())
            sink.write(myMessageVector.toByteArray())
            sink.flush()
            null
        }

        futures.receiver = ioExecutors.submit<BitSet> {
            val messageType = source.readByte()
            // TODO: Make an exception type for protocol errors
            if (messageType != Messages.MESSAGE_VECTOR.value)
                throw IOException("Expected a message vector but got $messageType")

            val theirMessageVectorByteArray = source.readByteArray(BloomFilter.SIZE_IN_BYTES.toLong())
            BitSet.valueOf(theirMessageVectorByteArray)
        }

        return futures
    }

    internal fun sendMessagesAsync(myMessages: Flowable<UnknownMessage>, sink: BufferedSink, connectionAddress : String) {
        Log.d(TAG, "Sending messages")
        var origin : String
        var destination : String
        var idMessage : Long
        var hopNumber : Int
        myMessages.subscribe({ message: UnknownMessage ->
            sink.writeByte(Messages.MESSAGE.value.toInt())
            message.writeToSink(sink)
            sink.flush()
            origin = BluetoothSyncService.Companion.macAddressFromLong(message.publicType.leastSignificantBits)
            destination = BluetoothSyncService.Companion.macAddressFromLong(message.publicType.mostSignificantBits)
            idMessage = message.internalId
            hopNumber = message.hopNumber
            NewSentMessageWriter(
                this.absolutePath,
                LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("HH:mm:ss")
                ),
                origin, destination, connectionAddress, idMessage, hopNumber, message.date).start()
            _qoSMetrics._bitsSent=_qoSMetrics._bitsSent+240
            _qoSMetrics._bitsTransmited=_qoSMetrics._bitsTransmited+240
            _qoSMetrics._initTime= Date()
        }, { e: Throwable ->
            Log.e(TAG, "Error sending messages", e)
            _qoSMetrics._bitsTransmited=_qoSMetrics._bitsTransmited-240
            _qoSMetrics._endTime=Date()
         }, {
            Log.d(TAG, "Sent messages")
            sink.writeByte(Messages.END.value.toInt())
            sink.flush()
            _qoSMetrics._endTime=Date()
            _qoSMetrics._success=true
        })
    }

    internal fun receiveMessagesAsync(source: BufferedSource, connectionAddress : String): Flowable<UnknownMessage> {
        Log.d(TAG, "Receiving messages")
        var receivedMessage : UnknownMessage
        var origin : String
        var destination : String
        var currentTime : Date
        return Flowable.create({ messageEmitter: FlowableEmitter<UnknownMessage> ->
            var messageCount = 0
            while (true) {
                val messageType = source.readByte()
                if (messageType == Messages.END.value)
                    break
                else if (messageType != Messages.MESSAGE.value)
                    messageEmitter.onError(IOException("Expected a message but got $messageType"))

                receivedMessage = UnknownMessage.fromSource(source)
                messageEmitter.onNext(receivedMessage)
                origin = BluetoothSyncService.Companion.macAddressFromLong(receivedMessage.publicType.leastSignificantBits)
                destination = BluetoothSyncService.Companion.macAddressFromLong(receivedMessage.publicType.mostSignificantBits)
                ++messageCount
                currentTime = Date()
                if(destination == BluetoothSyncService.getMacAddress()){
                    Log.d(TAG, "                Received messages as destination")
                    NewDeliveredMessageWriter(
                        this.absolutePath,
                        LocalDateTime.now().format(
                            DateTimeFormatter.ofPattern("HH:mm:ss")
                        ),
                        origin, destination, connectionAddress, receivedMessage.internalId, receivedMessage.hopNumber, currentTime.time-receivedMessage.date.time, receivedMessage.date).start()
                }else{
                    receivedMessage.hopNumber=receivedMessage.hopNumber+1
                    NewReceivedMessageWriter(
                        this.absolutePath,
                        LocalDateTime.now().format(
                            DateTimeFormatter.ofPattern("HH:mm:ss")
                        ),
                        origin, destination, connectionAddress, receivedMessage.internalId, receivedMessage.hopNumber, receivedMessage.date).start()
                }
                Log.w(TAG, "New message [$messageCount] From: $origin | Destination:$destination | Created: ${receivedMessage.date} | Received:${currentTime} | Latency: ${currentTime.time-receivedMessage.date.time} ")
            }

            messageEmitter.onComplete()
            Log.d(TAG, "Received $messageCount messages")
        }, BackpressureStrategy.BUFFER)
    }

    class NewDeliveredMessageWriter(
        private val absolutePath: File,
        private val _timestamp: String,
        private val _sourceNode: String,
        private val _destinationNode: String,
        private val _previousNode: String,
        private val idMessage: Long,
        private val hopNumber: Int,
        private val latency : Long,
        private val generatedDate : Date
    ) :
        Thread() {
        override fun run() {
            Log.w("NewDeliveredMessage", "New message delivered")
            addNewDeliveredMessage(
                absolutePath, Message(
                    _timestamp,
                    _sourceNode,
                    _destinationNode,
                    _previousNode,
                    idMessage,
                    hopNumber,
                    generatedDate
                ),latency
            )
        }
    }

    class NewReceivedMessageWriter(
        private val absolutePath: File,
        private val _timestamp: String,
        private val _sourceNode: String,
        private val _destinationNode: String,
        private val _previousNode: String,
        private val idMessage: Long,
        private val hopNumber: Int,
        private val generatedDate : Date
    ) :
        Thread() {
        override fun run() {
            Log.w("NewReceivedMessage", "New message received")
            addNewReceivedMessage(
                absolutePath, Message(
                    _timestamp,
                    _sourceNode,
                    _destinationNode,
                    _previousNode,
                    idMessage,
                    hopNumber,
                    generatedDate
                )
            )
        }
    }

    class NewSentMessageWriter(
        private val absolutePath: File,
        private val _timestamp: String,
        private val _sourceNode: String,
        private val _destinationNode: String,
        private val _previousNode: String,
        private val idMessage: Long,
        private val hopNumber: Int,
        private val generatedDate : Date
    ) :
        Thread() {
        override fun run() {
            Log.w("NewSentMessage", "New message sent")
            addNewForwardedMessage(
                absolutePath, Message(
                    _timestamp,
                    _sourceNode,
                    _destinationNode,
                    _previousNode,
                    idMessage,
                    hopNumber,
                    generatedDate
                )
            )
        }
    }
}
