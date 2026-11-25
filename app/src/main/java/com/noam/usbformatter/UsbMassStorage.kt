package com.noam.usbformatter

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

/** USB Mass Storage, Bulk-Only Transport + commandes SCSI, sans root. */
class UsbMassStorage(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint
) : BlockDev {

    private var bs = 512
    private var bc = 0L
    private var tag = 1
    private val bulkChunk = 16384
    private val scsiChunk = 131072

    override val blockSize get() = bs
    override val blockCount get() = bc

    private fun cmd(cdb: ByteArray, dataLen: Int, dirIn: Boolean, buf: ByteArray?, off: Int = 0) {
        val cbw = ByteBuffer.allocate(31).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355)
        cbw.putInt(tag++)
        cbw.putInt(dataLen)
        cbw.put(if (dirIn) 0x80.toByte() else 0.toByte())
        cbw.put(0.toByte())
        cbw.put(cdb.size.toByte())
        cbw.put(cdb)
        if (conn.bulkTransfer(outEp, cbw.array(), 31, 5000) != 31) throw Exception("Envoi de la commande USB échoué")
        if (dataLen > 0 && buf != null) {
            var done = 0
            while (done < dataLen) {
                val n = minOf(bulkChunk, dataLen - done)
                val r = conn.bulkTransfer(if (dirIn) inEp else outEp, buf, off + done, n, 15000)
                if (r <= 0) throw Exception("Transfert de données échoué")
                done += r
            }
        }
        val csw = ByteArray(13)
        var r = conn.bulkTransfer(inEp, csw, 13, 5000)
        if (r != 13) r = conn.bulkTransfer(inEp, csw, 13, 5000)
        if (r != 13) throw Exception("Réponse de la clé manquante")
        if (csw[12].toInt() != 0) throw Exception("La clé a refusé la commande (SCSI ${csw[12]})")
    }

    fun open() {
        if (!conn.claimInterface(iface, true)) throw Exception("Impossible de prendre le contrôle de la clé")
        for (i in 0 until 10) {
            try { cmd(bytes(0, 0, 0, 0, 0, 0), 0, false, null); break } catch (e: Exception) { Thread.sleep(500) }
        }
        val d = ByteArray(8)
        cmd(bytes(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), 8, true, d)
        val bb = ByteBuffer.wrap(d)
        val last = bb.int.toLong() and 0xFFFFFFFFL
        bs = bb.int
        bc = last + 1
        if (last == 0xFFFFFFFFL) {
            val d2 = ByteArray(32)
            cmd(bytes(0x9E, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32, 0, 0), 32, true, d2)
            val b2 = ByteBuffer.wrap(d2)
            bc = b2.long + 1
            bs = b2.int
        }
        if (bs <= 0 || bc <= 0) throw Exception("Capacité illisible")
    }

    fun close() {
        try { conn.releaseInterface(iface) } catch (_: Exception) {}
        conn.close()
    }

    private fun rw(write: Boolean, lba: Long, n: Int): ByteArray {
        val b: ByteBuffer
        if (bc > 0xFFFFFFFFL) {
            b = ByteBuffer.allocate(16)
            b.put((if (write) 0x8A else 0x88).toByte()); b.put(0.toByte())
            b.putLong(lba); b.putInt(n); b.put(0.toByte()); b.put(0.toByte())
        } else {
            b = ByteBuffer.allocate(10)
            b.put((if (write) 0x2A else 0x28).toByte()); b.put(0.toByte())
            b.putInt(lba.toInt()); b.put(0.toByte()); b.putShort(n.toShort()); b.put(0.toByte())
        }
        return b.array()
    }

    override fun read(lba: Long, blocks: Int): ByteArray {
        val out = ByteArray(blocks * bs)
        val per = scsiChunk / bs
        var done = 0
        while (done < blocks) {
            val n = minOf(per, blocks - done)
            cmd(rw(false, lba + done, n), n * bs, true, out, done * bs)
            done += n
        }
        return out
    }

    override fun write(lba: Long, data: ByteArray) {
        require(data.size % bs == 0) { "Écriture non alignée" }
        val blocks = data.size / bs
        val per = scsiChunk / bs
        var done = 0
        while (done < blocks) {
            val n = minOf(per, blocks - done)
            cmd(rw(true, lba + done, n), n * bs, false, data, done * bs)
            done += n
        }
    }

    companion object {
        fun find(dev: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
            for (i in 0 until dev.interfaceCount) {
                val itf = dev.getInterface(i)
                if (itf.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE) continue
                if (itf.interfaceSubclass != 6 || itf.interfaceProtocol != 0x50) continue
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
                if (inEp != null && outEp != null) return Triple(itf, inEp, outEp)
            }
            return null
        }

        fun open(manager: UsbManager, dev: UsbDevice): UsbMassStorage {
            val (itf, i, o) = find(dev) ?: throw Exception("Pas une clé de stockage USB")
            val conn = manager.openDevice(dev) ?: throw Exception("Accès refusé à la clé")
            val m = UsbMassStorage(conn, itf, i, o)
            try { m.open() } catch (e: Exception) { m.close(); throw e }
            return m
        }
    }
}
