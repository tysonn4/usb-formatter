package com.noam.usbformatter

import java.nio.ByteBuffer
import java.nio.ByteOrder

typealias Progress = (Int, String) -> Unit

object Formatters {
    private const val MB = 1048576
    /** GUID de type GPT : partition de données Windows (EBD0A0A2-B9E5-4433-87C0-68B6B72699C7) */
    val BASIC_DATA = intArrayOf(0xA2, 0xA0, 0xD0, 0xEB, 0xE5, 0xB9, 0x33, 0x44, 0x87, 0xC0, 0x68, 0xB6, 0xB7, 0x26, 0x99, 0xC7)
    /** GUID de type GPT : système de fichiers Linux (0FC63DAF-8483-4772-8E79-3D69D8477DE4) */
    val LINUX_FS = intArrayOf(0xAF, 0x3D, 0xC6, 0x0F, 0x83, 0x84, 0x72, 0x47, 0x8E, 0x79, 0x3D, 0x69, 0xD8, 0x47, 0x7D, 0xE4)

    internal fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    internal fun startLba(dev: BlockDev) = (MB / dev.blockSize).toLong()

    internal fun zero(dev: BlockDev, lba: Long, sectors: Long, p: Progress? = null, from: Int = 0, to: Int = 0) {
        val bs = dev.blockSize
        val chunk = (MB / bs).toLong()
        val z = ByteArray((chunk * bs).toInt())
        var done = 0L
        while (done < sectors) {
            val n = minOf(chunk, sectors - done)
            dev.write(lba + done, if (n == chunk) z else ByteArray((n * bs).toInt()))
            done += n
            if (p != null) p(from + ((to - from) * done / sectors).toInt(), "Écriture…")
        }
    }

    /** Efface les anciennes tables GPT (début et fin du disque). */
    internal fun wipeGpt(dev: BlockDev) {
        val bs = dev.blockSize
        dev.write(1, ByteArray(33 * bs))
        dev.write(dev.blockCount - 34, ByteArray(34 * bs))
    }

    private fun writeMbr(dev: BlockDev, start: Long, sectors: Long, type: Int) {
        val bs = dev.blockSize
        val a = ByteArray(bs)
        val b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        val o = 446
        if (type == 0xEE) {
            a[o + 2] = 0x02
            a[o + 5] = 0xFF.toByte(); a[o + 6] = 0xFF.toByte(); a[o + 7] = 0xFF.toByte()
        } else {
            a[o + 1] = 0xFE.toByte(); a[o + 2] = 0xFF.toByte(); a[o + 3] = 0xFF.toByte()
            a[o + 5] = 0xFE.toByte(); a[o + 6] = 0xFF.toByte(); a[o + 7] = 0xFF.toByte()
        }
        a[o + 4] = type.toByte()
        b.putInt(o + 8, start.toInt())
        b.putInt(o + 12, sectors.toInt())
        a[510] = 0x55; a[511] = 0xAA.toByte()
        dev.write(0, a)
    }

    private fun entriesSectors(dev: BlockDev) = (16384 + dev.blockSize - 1) / dev.blockSize
    internal fun lastUsable(dev: BlockDev) = dev.blockCount - 2 - entriesSectors(dev)

    private fun guid(u: java.util.UUID): ByteArray {
        val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        val m = u.mostSignificantBits
        b.putInt((m ushr 32).toInt()); b.putShort((m ushr 16).toShort()); b.putShort(m.toShort())
        b.order(ByteOrder.BIG_ENDIAN); b.putLong(u.leastSignificantBits)
        return b.array()
    }

    private fun writeGpt(dev: BlockDev, start: Long, sectors: Long, label: String, typeGuid: IntArray) {
        val bs = dev.blockSize
        val es = entriesSectors(dev)
        val last = dev.blockCount - 1
        val firstUsable = 2L + es
        val lastUsable = lastUsable(dev)
        val entries = ByteArray(es * bs)
        val eb = ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN)
        // Basic data partition : EBD0A0A2-B9E5-4433-87C0-68B6B72699C7
        typeGuid.forEachIndexed { i, v -> entries[i] = v.toByte() }
        guid(java.util.UUID.randomUUID()).copyInto(entries, 16)
        eb.putLong(32, start); eb.putLong(40, start + sectors - 1)
        val name = (if (label.isEmpty()) "USB" else label).take(30)
        name.toByteArray(Charsets.UTF_16LE).copyInto(entries, 56)
        val crc = java.util.zip.CRC32()
        crc.update(entries, 0, 16384)
        val entriesCrc = crc.value.toInt()
        val diskGuid = guid(java.util.UUID.randomUUID())

        fun header(my: Long, alt: Long, eLba: Long): ByteArray {
            val h = ByteArray(bs)
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            "EFI PART".toByteArray().copyInto(h, 0)
            b.putInt(8, 0x00010000); b.putInt(12, 92)
            b.putLong(24, my); b.putLong(32, alt)
            b.putLong(40, firstUsable); b.putLong(48, lastUsable)
            diskGuid.copyInto(h, 56)
            b.putLong(72, eLba); b.putInt(80, 128); b.putInt(84, 128); b.putInt(88, entriesCrc)
            val c = java.util.zip.CRC32()
            c.update(h, 0, 92)
            b.putInt(16, c.value.toInt())
            return h
        }
        dev.write(2, entries)
        dev.write(last - es, entries)
        dev.write(1, header(1, last, 2))
        dev.write(last, header(last, 1, last - es))
    }

    /** MBR classique, ou GPT (avec MBR de protection). */
    internal fun writeTable(dev: BlockDev, gpt: Boolean, start: Long, sectors: Long, mbrType: Int, label: String,
                            gptType: IntArray = BASIC_DATA) {
        if (gpt) {
            writeGpt(dev, start, sectors, label, gptType)
            writeMbr(dev, 1, minOf(dev.blockCount - 1, 0xFFFFFFFFL), 0xEE)
        } else {
            writeMbr(dev, start, sectors, mbrType)
        }
    }

    private fun checksum(b: ByteArray): Int {
        var s = 0
        for (x in b) s = ((s shl 31) or (s ushr 1)) + (x.toInt() and 0xFF)
        return s
    }

    /** Table de majuscules exFAT au format compressé (0xFFFF + n = n caractères identiques). */
    private fun buildUpcase(): ByteArray {
        fun up(i: Int) = Character.toUpperCase(i.toChar()).code
        var last = 0
        for (i in 0 until 65535) if (up(i) != i) last = i
        val e = ArrayList<Int>()
        var i = 0
        while (i <= last) {
            if (up(i) != i) { e.add(up(i)); i++; continue }
            var j = i
            while (j <= last && up(j) == j) j++
            val run = j - i
            if (run > 2) { e.add(0xFFFF); e.add(run) } else for (k in 0 until run) e.add(i + k)
            i = j
        }
        val b = ByteBuffer.allocate(e.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in e) b.putShort(v.toShort())
        return b.array()
    }

    internal fun check(dev: BlockDev, gpt: Boolean) {
        if (!gpt && dev.blockCount > 0xFFFFFFFFL) throw IllegalStateException("Disque trop grand pour une table MBR (> 2 To), choisis GPT")
        if (dev.blockCount * dev.blockSize < 64L * MB) throw IllegalStateException("Clé trop petite (< 64 Mo)")
    }

    fun formatFat32(dev: BlockDev, label: String, gpt: Boolean, p: Progress) {
        check(dev, gpt)
        val bs = dev.blockSize
        val start = startLba(dev)
        val vol = minOf(if (gpt) lastUsable(dev) - start + 1 else dev.blockCount - start, 0xFFFFFFFFL)
        val volBytes = vol * bs
        val clusterBytes = when {
            volBytes <= 8589934592L -> 4096
            volBytes <= 17179869184L -> 8192
            volBytes <= 34359738368L -> 16384
            else -> 32768
        }
        var spc = maxOf(1, clusterBytes / bs)
        val reserved = 32L
        val nFats = 2L
        var fatSz: Long
        var clusters: Long
        while (true) {
            val t1 = vol - reserved
            val t2 = ((bs / 4L) * spc + nFats) / 2
            fatSz = (t1 + t2 - 1) / t2
            clusters = (vol - reserved - nFats * fatSz) / spc
            if (clusters >= 65525 || spc == 1) break
            spc /= 2
        }
        if (clusters < 65525) throw IllegalStateException("Clé trop petite pour FAT32")
        if (clusters > 0x0FFFFFF0L) throw IllegalStateException("Trop de clusters pour FAT32")

        p(1, "Nettoyage des anciennes tables…")
        wipeGpt(dev)

        val lab = label.uppercase().filter { it.code in 32..126 && it !in "\"*+,./:;<=>?[\\]|" }.take(11)
        val lab11 = (if (lab.isEmpty()) "NO NAME" else lab).padEnd(11)

        val b = le(bs); val a = b.array()
        a[0] = 0xEB.toByte(); a[1] = 0x58; a[2] = 0x90.toByte()
        "MSDOS5.0".toByteArray().copyInto(a, 3)
        b.putShort(11, bs.toShort()); a[13] = spc.toByte(); b.putShort(14, reserved.toShort()); a[16] = nFats.toByte()
        a[21] = 0xF8.toByte(); b.putShort(24, 63); b.putShort(26, 255)
        b.putInt(28, start.toInt()); b.putInt(32, vol.toInt()); b.putInt(36, fatSz.toInt())
        b.putInt(44, 2); b.putShort(48, 1); b.putShort(50, 6)
        a[64] = 0x80.toByte(); a[66] = 0x29
        b.putInt(67, java.util.Random().nextInt())
        lab11.toByteArray().copyInto(a, 71)
        "FAT32   ".toByteArray().copyInto(a, 82)
        a[510] = 0x55; a[511] = 0xAA.toByte()

        val f = le(bs)
        f.putInt(0, 0x41615252); f.putInt(484, 0x61417272)
        f.putInt(488, (clusters - 1).toInt()); f.putInt(492, 3)
        f.putInt(508, 0xAA550000.toInt())

        val tail = ByteArray(bs); tail[bs - 2] = 0x55; tail[bs - 1] = 0xAA.toByte()

        p(2, "Zone réservée…")
        zero(dev, start, reserved)
        dev.write(start, a); dev.write(start + 6, a)
        dev.write(start + 1, f.array()); dev.write(start + 7, f.array())
        dev.write(start + 2, tail); dev.write(start + 8, tail)

        zero(dev, start + reserved, nFats * fatSz, p, 5, 90)
        val fh = le(bs)
        fh.putInt(0, 0x0FFFFFF8); fh.putInt(4, 0x0FFFFFFF); fh.putInt(8, 0x0FFFFFFF)
        for (i in 0 until nFats) dev.write(start + reserved + i * fatSz, fh.array())

        p(95, "Dossier racine…")
        val rootLba = start + reserved + nFats * fatSz
        val root = ByteArray(spc * bs)
        if (lab.isNotEmpty()) { lab11.toByteArray().copyInto(root, 0); root[11] = 0x08 }
        dev.write(rootLba, root)

        p(98, "Table de partition…")
        writeTable(dev, gpt, start, vol, 0x0C, lab)
        p(100, "Terminé : FAT32")
    }

    fun formatFat16(dev: BlockDev, label: String, gpt: Boolean, p: Progress) {
        check(dev, gpt)
        val bs = dev.blockSize
        val start = startLba(dev)
        var vol = minOf(if (gpt) lastUsable(dev) - start + 1 else dev.blockCount - start, 0xFFFFFFFFL)
        val maxSpc = 65536 / bs                       // clusters de 64 Ko maximum
        val reserved = 1L
        val nFats = 2L
        val rootEntries = 512
        val rootSectors = (rootEntries * 32L + bs - 1) / bs
        // FAT16 : 65 524 clusters de 64 Ko au maximum (environ 4 Go)
        val capSectors = 4000L * MB / bs
        val capped = vol > capSectors
        if (capped) vol = capSectors

        var spc = 1
        var fatSz: Long
        var clusters: Long
        while (true) {
            val t1 = vol - reserved - rootSectors
            val t2 = (bs / 2L) * spc + nFats
            fatSz = (t1 + t2 - 1) / t2
            clusters = (vol - reserved - rootSectors - nFats * fatSz) / spc
            if (clusters <= 65524 || spc >= maxSpc) break
            spc *= 2
        }
        if (clusters > 65524) throw IllegalStateException("Clé trop grande pour FAT16")
        if (clusters < 4085) throw IllegalStateException("Clé trop petite pour FAT16")

        p(1, "Nettoyage des anciennes tables…")
        wipeGpt(dev)

        val lab = label.uppercase().filter { it.code in 32..126 && it !in "\"*+,./:;<=>?[\\]|" }.take(11)
        val lab11 = (if (lab.isEmpty()) "NO NAME" else lab).padEnd(11)

        val b = le(bs); val a = b.array()
        a[0] = 0xEB.toByte(); a[1] = 0x3C; a[2] = 0x90.toByte()
        "MSDOS5.0".toByteArray().copyInto(a, 3)
        b.putShort(11, bs.toShort()); a[13] = spc.toByte(); b.putShort(14, reserved.toShort()); a[16] = nFats.toByte()
        b.putShort(17, rootEntries.toShort())
        if (vol < 65536) b.putShort(19, vol.toShort()) else b.putInt(32, vol.toInt())
        a[21] = 0xF8.toByte(); b.putShort(22, fatSz.toShort()); b.putShort(24, 63); b.putShort(26, 255)
        b.putInt(28, start.toInt())
        a[36] = 0x80.toByte(); a[38] = 0x29
        b.putInt(39, java.util.Random().nextInt())
        lab11.toByteArray().copyInto(a, 43)
        "FAT16   ".toByteArray().copyInto(a, 54)
        a[510] = 0x55; a[511] = 0xAA.toByte()
        dev.write(start, a)

        zero(dev, start + reserved, nFats * fatSz, p, 5, 90)
        val fh = le(bs)
        fh.putShort(0, 0xFFF8.toShort()); fh.putShort(2, 0xFFFF.toShort())
        for (i in 0 until nFats) dev.write(start + reserved + i * fatSz, fh.array())

        p(95, "Dossier racine…")
        val rootLba = start + reserved + nFats * fatSz
        val root = ByteArray((rootSectors * bs).toInt())
        if (lab.isNotEmpty()) { lab11.toByteArray().copyInto(root, 0); root[11] = 0x08 }
        dev.write(rootLba, root)

        p(98, "Table de partition…")
        writeTable(dev, gpt, start, vol, 0x0E, lab)
        p(100, if (capped) "Terminé : FAT16 (partition limitée à 4 Go)" else "Terminé : FAT16")
    }

    fun formatExFat(dev: BlockDev, label: String, gpt: Boolean, p: Progress) {
        check(dev, gpt)
        val bs = dev.blockSize
        val start = startLba(dev)
        val vol = if (gpt) lastUsable(dev) - start + 1 else dev.blockCount - start
        val volBytes = vol * bs
        val clusterBytes = when {
            volBytes <= 268435456L -> 4096
            volBytes <= 34359738368L -> 32768
            else -> 131072
        }
        val spc = maxOf(1, clusterBytes / bs)
        val cb = spc * bs
        val fatOffset = 24L
        val cc0 = (vol - fatOffset) / spc
        val fatLen = ((cc0 + 2) * 4 + bs - 1) / bs
        val heapOff = ((fatOffset + fatLen + spc - 1) / spc) * spc
        val cc = (vol - heapOff) / spc
        if (cc < 64) throw IllegalStateException("Clé trop petite pour exFAT")
        val up = buildUpcase()
        val upBytes = up.size
        val bitmapBytes = ((cc + 7) / 8).toInt()
        val bmClusters = (bitmapBytes + cb - 1) / cb
        val upClusters = (upBytes + cb - 1) / cb
        val rootCluster = 2 + bmClusters + upClusters
        val used = bmClusters + upClusters + 1

        p(1, "Nettoyage des anciennes tables…")
        wipeGpt(dev)

        val upSum = checksum(up)

        val region = ByteArray(12 * bs)
        val rb = ByteBuffer.wrap(region).order(ByteOrder.LITTLE_ENDIAN)
        region[0] = 0xEB.toByte(); region[1] = 0x76; region[2] = 0x90.toByte()
        "EXFAT   ".toByteArray().copyInto(region, 3)
        rb.putLong(64, start); rb.putLong(72, vol)
        rb.putInt(80, fatOffset.toInt()); rb.putInt(84, fatLen.toInt())
        rb.putInt(88, heapOff.toInt()); rb.putInt(92, cc.toInt()); rb.putInt(96, rootCluster)
        rb.putInt(100, java.util.Random().nextInt())
        rb.putShort(104, 0x0100)
        region[108] = Integer.numberOfTrailingZeros(bs).toByte()
        region[109] = Integer.numberOfTrailingZeros(spc).toByte()
        region[110] = 1; region[111] = 0x80.toByte(); region[112] = 0xFF.toByte()
        region[510] = 0x55; region[511] = 0xAA.toByte()
        for (k in 1..8) { val o = k * bs + bs - 4; region[o + 2] = 0x55; region[o + 3] = 0xAA.toByte() }
        var sum = 0
        for (i in 0 until 11 * bs) {
            if (i == 106 || i == 107 || i == 112) continue
            sum = ((sum shl 31) or (sum ushr 1)) + (region[i].toInt() and 0xFF)
        }
        for (i in 0 until bs / 4) rb.putInt(11 * bs + i * 4, sum)
        dev.write(start, region)
        dev.write(start + 12, region)

        zero(dev, start + fatOffset, fatLen, p, 5, 90)
        val fatBytes = ((2 + used) * 4 + bs - 1) / bs * bs
        val fb = ByteBuffer.allocate(fatBytes).order(ByteOrder.LITTLE_ENDIAN)
        fb.putInt(0, -8); fb.putInt(4, -1)
        fun chain(first: Int, n: Int) { for (i in 0 until n) fb.putInt((first + i) * 4, if (i == n - 1) -1 else first + i + 1) }
        chain(2, bmClusters); chain(2 + bmClusters, upClusters); chain(rootCluster, 1)
        dev.write(start + fatOffset, fb.array())

        p(92, "Tables du volume…")
        fun clusterLba(c: Int) = start + heapOff + (c - 2).toLong() * spc
        val bm = ByteArray(bmClusters * cb)
        for (i in 0 until used) bm[i / 8] = (bm[i / 8].toInt() or (1 shl (i % 8))).toByte()
        dev.write(clusterLba(2), bm)
        dev.write(clusterLba(2 + bmClusters), up.copyOf(upClusters * cb))

        val root = ByteArray(cb)
        val rr = ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN)
        var o = 0
        val lab = label.take(11)
        if (lab.isNotEmpty()) {
            root[o] = 0x83.toByte(); root[o + 1] = lab.length.toByte()
            for (i in lab.indices) rr.putShort(o + 2 + i * 2, lab[i].code.toShort())
            o += 32
        }
        root[o] = 0x81.toByte(); rr.putInt(o + 20, 2); rr.putLong(o + 24, bitmapBytes.toLong()); o += 32
        root[o] = 0x82.toByte(); rr.putInt(o + 4, upSum); rr.putInt(o + 20, 2 + bmClusters); rr.putLong(o + 24, upBytes.toLong())
        dev.write(clusterLba(rootCluster), root)

        p(98, "Table de partition…")
        writeTable(dev, gpt, start, vol, 0x07, label)
        p(100, "Terminé : exFAT")
    }
}
