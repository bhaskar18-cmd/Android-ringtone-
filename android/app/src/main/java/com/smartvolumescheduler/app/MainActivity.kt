package com.smartvolumescheduler.app

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(VolumeSchedulerPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
