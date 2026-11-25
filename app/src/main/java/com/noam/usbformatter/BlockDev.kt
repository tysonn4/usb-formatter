package com.noam.usbformatter

interface BlockDev {
    val blockSize: Int
    val blockCount: Long
    fun write(lba: Long, data: ByteArray)
    fun read(lba: Long, blocks: Int): ByteArray
}
