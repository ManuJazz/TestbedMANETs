package com.manetevaluation.pong.sync

import java.util.Date

class QoSMetrics(var bitsSent : Long, var bitsTransmited : Long, var initTime : Date) {
    var _bitsSent = bitsSent
    var _bitsTransmited = bitsTransmited
    var _initTime = initTime
    var _endTime : Date = Date(_initTime.time)
    var _success : Boolean = false
}