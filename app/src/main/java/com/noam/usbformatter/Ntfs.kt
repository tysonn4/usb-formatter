package com.noam.usbformatter

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Formateur NTFS (formatage rapide), clusters de 4 Ko, secteurs de 512 octets.
 * Reprend la structure exacte produite par mkntfs (fichiers système, $Secure, $UpCase, $Extend…)
 * à partir d'un modèle (ntfs_template.bin) et adapte les tailles et positions au volume.
 */
object Ntfs {
    private const val CS = 4096
    private const val SPC = 8
    private const val REC = 1024
    private const val NREC = 27
    private const val USN = 2

    // Disposition du modèle (ntfs_template.bin)
    private const val O_RECS = 0
    private const val O_INDX = 27648
    private const val O_ROOTSD = 31744
    private const val L_ROOTSD = 4140
    private const val O_ATTRDEF = 35884
    private const val L_ATTRDEF = 2560
    private const val O_BOOT = 38444
    private const val O_UPCASE = 46636
    private const val L_UPCASE = 131072
    private const val O_SDS = 177708
    private const val L_SDS = 252
    private const val O_MFTBM = 177960
    private const val SDS_CLUSTERS = 65
    private const val UPCASE_CLUSTERS = 32

    // Date de création inscrite dans le modèle, remplacée par l'heure actuelle
    private val T0 = byteArrayOf(0x80.toByte(), 0x7c, 0xc0.toByte(), 0xac.toByte(), 0xed.toByte(), 0x7d, 0xdd.toByte(), 0x01)

    private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun wrap(a: ByteArray): ByteBuffer = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
    private fun align8(n: Int) = (n + 7) and 7.inv()

    private fun template(): ByteArray {
        val s = Ntfs::class.java.getResourceAsStream("/ntfs_template.bin")
            ?: throw IllegalStateException("Modèle NTFS introuvable")
        return s.use { it.readBytes() }
    }

    private fun replaceAll(buf: ByteArray, from: ByteArray, to: ByteArray) {
        var i = 0
        while (i <= buf.size - 8) {
            var m = true
            for (k in 0 until 8) if (buf[i + k] != from[k]) { m = false; break }
            if (m) { to.copyInto(buf, i); i += 8 } else i++
        }
    }

    private fun applyFixups(buf: ByteArray, usaOff: Int, usaCount: Int) {
        val b = wrap(buf)
        b.putShort(usaOff, USN.toShort())
        for (i in 1 until usaCount) {
            val pos = i * 512 - 2
            buf[usaOff + 2 * i] = buf[pos]; buf[usaOff + 2 * i + 1] = buf[pos + 1]
            b.putShort(pos, USN.toShort())
        }
    }

    // ---------- listes de clusters (runlists) ----------
    private fun minUnsigned(v: Long): Int { var n = 1; var x = v ushr 8; while (x != 0L) { n++; x = x ushr 8 }; return n }
    private fun minSigned(v: Long): Int {
        var n = 1
        while (n < 8) { val lim = 1L shl (8 * n - 1); if (v >= -lim && v < lim) break; n++ }
        return n
    }
    private fun putLe(o: ByteArrayOutputStream, v: Long, n: Int) { for (i in 0 until n) o.write(((v shr (8 * i)) and 0xFF).toInt()) }

    /** runs : paires (lcn, longueur) ; lcn = -1 pour une plage « sparse ». */
    private fun runlist(runs: List<LongArray>): ByteArray {
        val o = ByteArrayOutputStream()
        var prev = 0L
        for (r in runs) {
            val lb = minUnsigned(r[1])
            if (r[0] < 0) { o.write(lb); putLe(o, r[1], lb) }
            else {
                val delta = r[0] - prev
                val ob = minSigned(delta)
                o.write((ob shl 4) or lb); putLe(o, r[1], lb); putLe(o, delta, ob)
                prev = r[0]
            }
        }
        o.write(0)
        return o.toByteArray()
    }

    private fun nonRes(type: Int, name: String, inst: Int, runs: List<LongArray>, lastVcn: Long, alloc: Long, real: Long, init: Long): ByteArray {
        val nb = name.toByteArray(Charsets.UTF_16LE)
        val runOff = 64 + align8(nb.size)
        val rl = runlist(runs)
        val len = align8(runOff + rl.size)
        val a = ByteArray(len)
        val b = wrap(a)
        b.putInt(0, type); b.putInt(4, len); a[8] = 1; a[9] = name.length.toByte()
        b.putShort(10, 64); b.putShort(14, inst.toShort())
        b.putLong(24, lastVcn); b.putShort(32, runOff.toShort())
        b.putLong(40, alloc); b.putLong(48, real); b.putLong(56, init)
        nb.copyInto(a, 64); rl.copyInto(a, runOff)
        return a
    }

    // ---------- enregistrements MFT ----------
    private class Rec(src: ByteArray) {
        val header: ByteArray = src.copyOfRange(0, 56)
        val attrs = ArrayList<ByteArray>()

        init {
            val b = wrap(src)
            var p = 56
            while (b.getInt(p) != -1) {
                val l = b.getInt(p + 4)
                attrs.add(src.copyOfRange(p, p + l))
                p += l
            }
        }

        fun type(a: ByteArray) = wrap(a).getInt(0)
        fun name(a: ByteArray): String {
            val n = a[9].toInt() and 0xFF
            if (n == 0) return ""
            val off = wrap(a).getShort(10).toInt()
            return String(a, off, n * 2, Charsets.UTF_16LE)
        }
        fun index(type: Int, name: String = ""): Int {
            val i = attrs.indexOfFirst { type(it) == type && name(it) == name }
            if (i < 0) throw IllegalStateException("Attribut NTFS introuvable : $type $name")
            return i
        }
        fun inst(a: ByteArray) = wrap(a).getShort(14).toInt() and 0xFFFF

        fun setNonRes(type: Int, name: String, runs: List<LongArray>, lastVcn: Long, alloc: Long, real: Long, init: Long) {
            val i = index(type, name)
            attrs[i] = nonRes(type, name, inst(attrs[i]), runs, lastVcn, alloc, real, init)
        }

        fun setResidentValue(type: Int, value: ByteArray) {
            val i = index(type)
            val old = attrs[i]
            val len = 24 + align8(value.size)
            val a = ByteArray(len)
            old.copyInto(a, 0, 0, 24)
            val b = wrap(a)
            b.putInt(4, len); b.putInt(16, value.size)
            value.copyInto(a, 24)
            attrs[i] = a
        }

        /** Taille allouée / réelle dans l'attribut $FILE_NAME. */
        fun setFileNameSizes(alloc: Long, real: Long) {
            val a = attrs[index(0x30)]
            val b = wrap(a)
            b.putLong(24 + 0x28, alloc); b.putLong(24 + 0x30, real)
        }

        fun build(): ByteArray {
            val out = ByteArray(REC)
            header.copyInto(out)
            var pos = 56
            for (a in attrs) { a.copyInto(out, pos); pos += a.size }
            wrap(out).putInt(pos, -1)
            pos += 8
            wrap(out).putInt(0x18, pos)
            return out
        }
    }

    private fun ntfsTime(): ByteArray {
        val t = System.currentTimeMillis() * 10000L + 116444736000000000L
        return le(8).putLong(0, t).array()
    }

    private fun setBits(bm: ByteArray, from: Long, count: Long) {
        for (i in from until from + count) {
            val k = i.toInt() ushr 3
            bm[k] = (bm[k].toInt() or (1 shl (i.toInt() and 7))).toByte()
        }
    }

    fun format(dev: BlockDev, label: String, gpt: Boolean, p: Progress) {
        Formatters.check(dev, gpt)
        if (dev.blockSize != 512) throw IllegalStateException("NTFS : seuls les disques à secteurs de 512 octets sont gérés pour l'instant")
        val start = Formatters.startLba(dev)
        val vol = if (gpt) Formatters.lastUsable(dev) - start + 1 else dev.blockCount - start
        val totalSectors = vol - 1
        val n = totalSectors / SPC                          // nombre de clusters
        val mirr = n / 2                                    // $MFTMirr au milieu du volume
        val logClusters = if (vol * 512L <= 200L * 1048576L) 512L else minOf(n / 200, 16384L)  // taille du $LogFile
        val m = (n + 7) / 8
        val bmBytes = ((n + 63) / 64) * 8
        val bmClusters = (bmBytes + CS - 1) / CS
        val lcnRootSd = m + 3
        val lcnIndx = m + 5
        val lcnAttrdef = m + 6
        val lcnBitmap = m + 7
        val lcnSds = lcnBitmap + bmClusters
        val lcnUpcase = lcnSds + SDS_CLUSTERS
        if (lcnUpcase + UPCASE_CLUSTERS >= mirr) throw IllegalStateException("Clé trop petite pour NTFS")

        val tpl = template()
        val now = ntfsTime()
        val labelBytes = label.take(32).toByteArray(Charsets.UTF_16LE)

        p(1, "Nettoyage des anciennes tables…")
        Formatters.wipeGpt(dev)

        // --- enregistrements MFT ---
        val recs = Array(NREC) { Rec(tpl.copyOfRange(O_RECS + it * REC, O_RECS + (it + 1) * REC)) }
        recs[1].setNonRes(0x80, "", listOf(longArrayOf(mirr, 1)), 0, CS.toLong(), CS.toLong(), CS.toLong())
        val logBytes = logClusters * CS
        recs[2].setNonRes(0x80, "", listOf(longArrayOf(mirr + 1, logClusters)), logClusters - 1, logBytes, logBytes, logBytes)
        recs[2].setFileNameSizes(logBytes, logBytes)
        recs[3].setResidentValue(0x60, labelBytes)
        recs[4].setNonRes(0x80, "", listOf(longArrayOf(lcnAttrdef, 1)), 0, CS.toLong(), L_ATTRDEF.toLong(), L_ATTRDEF.toLong())
        recs[5].setNonRes(0x50, "", listOf(longArrayOf(lcnRootSd, 2)), 1, 2L * CS, L_ROOTSD.toLong(), L_ROOTSD.toLong())
        recs[5].setNonRes(0xA0, "\$I30", listOf(longArrayOf(lcnIndx, 1)), 0, CS.toLong(), CS.toLong(), CS.toLong())
        recs[6].setNonRes(0x80, "", listOf(longArrayOf(lcnBitmap, bmClusters)), bmClusters - 1, bmClusters * CS, bmBytes, bmBytes)
        recs[6].setFileNameSizes(bmClusters * CS, bmBytes)
        recs[8].setNonRes(0x80, "\$Bad", listOf(longArrayOf(-1, n)), n - 1, n * CS, n * CS, 0)
        recs[9].setNonRes(0x80, "\$SDS", listOf(longArrayOf(lcnSds, SDS_CLUSTERS.toLong())), SDS_CLUSTERS - 1L,
            SDS_CLUSTERS.toLong() * CS, 262144L + L_SDS, 262144L + L_SDS)
        recs[10].setNonRes(0x80, "", listOf(longArrayOf(lcnUpcase, UPCASE_CLUSTERS.toLong())), UPCASE_CLUSTERS - 1L,
            L_UPCASE.toLong(), L_UPCASE.toLong(), L_UPCASE.toLong())

        val mft = ByteArray(7 * CS)
        for (i in 0 until NREC) {
            val r = recs[i].build()
            replaceAll(r, T0, now)
            applyFixups(r, 48, 3)
            r.copyInto(mft, i * REC)
        }

        // --- index de la racine (bloc INDX) ---
        val indx = tpl.copyOfRange(O_INDX, O_INDX + CS)
        replaceAll(indx, T0, now)
        run {
            val b = wrap(indx)
            var e = 0x18 + b.getInt(0x18)
            while (true) {
                val recNo = b.getLong(e) and 0xFFFFFFFFFFFFL
                val len = b.getShort(e + 8).toInt() and 0xFFFF
                val flags = b.getShort(e + 12).toInt() and 0xFFFF
                if (flags and 2 != 0) break
                if (recNo == 2L) { b.putLong(e + 16 + 0x28, logBytes); b.putLong(e + 16 + 0x30, logBytes) }
                if (recNo == 6L) { b.putLong(e + 16 + 0x28, bmClusters * CS); b.putLong(e + 16 + 0x30, bmBytes) }
                e += len
            }
        }
        applyFixups(indx, 40, 9)

        // --- secteur de démarrage ---
        val boot = ByteArray(2 * CS)
        tpl.copyInto(boot, 0, O_BOOT, O_BOOT + 512)
        run {
            val b = wrap(boot)
            b.putInt(28, start.toInt())
            b.putLong(40, totalSectors)
            b.putLong(56, mirr)
            b.putLong(72, java.util.Random().nextLong())
        }

        fun wc(lcn: Long, data: ByteArray) = dev.write(start + lcn * SPC, data)
        fun padTo(a: ByteArray, size: Int) = if (a.size == size) a else a.copyOf(size)

        p(3, "Fichiers système…")
        wc(0, boot)
        wc(2, padTo(tpl.copyOfRange(O_MFTBM, O_MFTBM + 8), CS))
        wc(4, mft)
        wc(mirr, mft.copyOf(4 * REC))
        wc(lcnRootSd, padTo(tpl.copyOfRange(O_ROOTSD, O_ROOTSD + L_ROOTSD), 2 * CS))
        wc(lcnIndx, indx)
        wc(lcnAttrdef, padTo(tpl.copyOfRange(O_ATTRDEF, O_ATTRDEF + L_ATTRDEF), CS))
        val sds = ByteArray(SDS_CLUSTERS * CS)
        tpl.copyInto(sds, 0, O_SDS, O_SDS + L_SDS)
        tpl.copyInto(sds, 262144, O_SDS, O_SDS + L_SDS)
        wc(lcnSds, sds)
        wc(lcnUpcase, tpl.copyOfRange(O_UPCASE, O_UPCASE + L_UPCASE))

        // --- bitmap des clusters ---
        p(8, "Bitmap des clusters…")
        val bm = ByteArray((bmClusters * CS).toInt())
        setBits(bm, 0, 3)
        setBits(bm, 4, 7)
        setBits(bm, lcnRootSd, 2L + 1 + 1 + bmClusters + SDS_CLUSTERS + UPCASE_CLUSTERS)
        setBits(bm, mirr, 1L + logClusters)
        setBits(bm, n, bmBytes * 8 - n)
        run {
            val chunk = 1 shl 20
            var off = 0
            while (off < bm.size) {
                val len = minOf(chunk, bm.size - off)
                dev.write(start + (lcnBitmap * CS + off) / 512, bm.copyOfRange(off, off + len))
                off += len
            }
        }

        // --- journal $LogFile (rempli de 0xFF) ---
        val ff = ByteArray(1 shl 20) { 0xFF.toByte() }
        var done = 0L
        while (done < logBytes) {
            val len = minOf(ff.size.toLong(), logBytes - done).toInt()
            dev.write(start + ((mirr + 1) * CS + done) / 512, if (len == ff.size) ff else ff.copyOf(len))
            done += len
            p(10 + (85 * done / logBytes).toInt(), "Journal NTFS…")
        }

        // --- copie de sauvegarde du secteur de démarrage (dernier secteur) ---
        dev.write(start + vol - 1, boot.copyOf(512))

        p(98, "Table de partition…")
        Formatters.writeTable(dev, gpt, start, vol, 0x07, label)
        p(100, "Terminé : NTFS")
    }
}
