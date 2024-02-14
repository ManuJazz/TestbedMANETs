package com.manetevaluation.pong

import android.app.Application
import com.manetevaluation.pong.sync.StreamSync

import com.raizlabs.android.dbflow.config.FlowConfig
import com.raizlabs.android.dbflow.config.FlowManager

import com.manetevaluation.pong.sync.bluetooth.BluetoothSyncService
import com.jakewharton.threetenabp.AndroidThreeTen

class PongApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidThreeTen.init(this);
        FlowManager.init(FlowConfig.Builder(this).build())
        BluetoothSyncService.startOrPromptBluetooth(this)
        StreamSync.setLongTimeContext(applicationContext)
    }
}
