package com.noam.usbformatter

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.SplittableRandom
import java.util.TreeSet

/**
 * Détecteur de fausse capacité (clés contrefaites). Test DESTRUCTIF : écrit des données
 * pseudo-aléatoires uniques puis les relit pour vérifier que chaque octet est bien conservé.
 */
object CapacityTest {
    class Result(val ok: Boolean, val text: String)

    private const val CHUNK = 1 shl 20
    private const val SMALL = 64 * 1024

    private fun fill(buf: ByteArray, len: Int, seed: Long) {
        val r = SplittableRandom(seed * -7046029254386353131L + 0x1234567L)
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 8 <= len) { b.putLong(i, r.nextLong()); i += 8 }
    }

    private fun gb(bytes: Long) = String.format("%.1f Go", bytes / 1e9)

    private fun wipeStart(dev: BlockDev) {
        try { dev.write(0, ByteArray(minOf(CHUNK.toLong(), dev.blockCount * dev.blockSize).toInt())) } catch (_: Exception) {}
    }

    private fun cancelled(dev: BlockDev): Result { wipeStart(dev); return Result(false, "Test annulé.") }

    /** Test complet : écrit toute la clé puis la relit. Long (plusieurs minutes à plusieurs heures). */
    fun full(dev: BlockDev, p: Progress, isCancelled: () -> Boolean): Result {
        val bs = dev.blockSize
        val total = dev.blockCount * bs
        val base = java.util.Random().nextLong()
        val chunks = (total + CHUNK - 1) / CHUNK
        val buf = ByteArray(CHUNK)
        var written = chunks
        var ioError: String? = null

        for (i in 0 until chunks) {
            if (isCancelled()) return cancelled(dev)
            val len = minOf(CHUNK.toLong(), total - i * CHUNK).toInt()
            val data = if (len == CHUNK) buf else ByteArray(len)
            fill(data, len, base + i)
            try { dev.write(i * CHUNK / bs, data) } catch (e: Exception) { written = i; ioError = e.message; break }
            p((i * 50 / chunks).toInt(), "Écriture… ${i * 100 / chunks} %")
        }

        var goodBytes = 0L
        var firstBad = -1L
        val exp = ByteArray(CHUNK)
        for (i in 0 until written) {
            if (isCancelled()) return cancelled(dev)
            val len = minOf(CHUNK.toLong(), total - i * CHUNK).toInt()
            val e = if (len == CHUNK) exp else ByteArray(len)
            fill(e, len, base + i)
            val got = try { dev.read(i * CHUNK / bs, len / bs) } catch (_: Exception) { null }
            if (got != null && Arrays.equals(got, e)) goodBytes += len
            else if (firstBad < 0) firstBad = i * CHUNK
            p(50 + (i * 50 / chunks).toInt(), "Vérification… ${i * 100 / chunks} %")
        }
        wipeStart(dev)
        p(100, "Test terminé")

        return if (ioError == null && goodBytes == total) {
            Result(true, "Capacité réelle confirmée : ${gb(total)}. Toutes les données ont été relues correctement.\n\nLa clé est maintenant vide : formate-la avant de l'utiliser.")
        } else {
            val pct = if (total > 0) goodBytes * 100 / total else 0
            Result(false,
                "Fausse capacité ou clé défectueuse.\n\n" +
                    "Capacité annoncée : ${gb(total)}\nCapacité réellement fiable : environ ${gb(goodBytes)} ($pct %)\n" +
                    (if (firstBad >= 0) "Premières données fausses à environ ${gb(firstBad)}.\n" else "") +
                    (if (ioError != null) "Erreur d'écriture : $ioError\n" else "") +
                    "\nFormate la clé avant de l'utiliser, en gardant à l'esprit qu'elle perdra des données au-delà de sa vraie taille.")
        }
    }

    /**
     * Test rapide (probabiliste) : écrit un motif sur le début de la clé, puis des milliers de petits
     * blocs répartis sur toute la capacité, et vérifie que rien n'a été écrasé ni perdu.
     */
    fun quick(dev: BlockDev, p: Progress, isCancelled: () -> Boolean): Result {
        val bs = dev.blockSize
        val total = dev.blockCount * bs
        if (total < (128L shl 20)) return full(dev, p, isCancelled)
        val base = java.util.Random().nextLong()
        val low = (minOf(64L shl 20, total / 4) / CHUNK) * CHUNK
        val lowChunks = (low / CHUNK).toInt()
        val buf = ByteArray(CHUNK)

        for (i in 0 until lowChunks) {
            if (isCancelled()) return cancelled(dev)
            fill(buf, CHUNK, base + i)
            dev.write(i * CHUNK.toLong() / bs, buf)
            p(i * 10 / lowChunks, "Zone de départ…")
        }

        val rnd = java.util.Random()
        val wanted = minOf(2000L, (total - low) / SMALL / 2).toInt()
        val pos = TreeSet<Long>()
        pos.add(total - SMALL)
        pos.add((total / 2 / SMALL) * SMALL)
        val slots = (total - low - SMALL) / SMALL
        while (pos.size < wanted) pos.add(low + (rnd.nextLong() and Long.MAX_VALUE) % slots * SMALL)
        val list = pos.toList()
        val small = ByteArray(SMALL)

        for ((k, off) in list.withIndex()) {
            if (isCancelled()) return cancelled(dev)
            fill(small, SMALL, base + 1_000_000 + k)
            dev.write(off / bs, small)
            p(10 + k * 50 / list.size, "Écriture d'échantillons… ${k * 100 / list.size} %")
        }

        var lowBad = 0
        val exp = ByteArray(CHUNK)
        for (i in 0 until lowChunks) {
            if (isCancelled()) return cancelled(dev)
            fill(exp, CHUNK, base + i)
            val got = dev.read(i * CHUNK.toLong() / bs, CHUNK / bs)
            if (!Arrays.equals(got, exp)) lowBad++
            p(60 + i * 10 / lowChunks, "Vérification du début…")
        }

        var sampleBad = 0
        var firstBadPos = -1L
        val exps = ByteArray(SMALL)
        for ((k, off) in list.withIndex()) {
            if (isCancelled()) return cancelled(dev)
            fill(exps, SMALL, base + 1_000_000 + k)
            val got = try { dev.read(off / bs, SMALL / bs) } catch (_: Exception) { null }
            if (got == null || !Arrays.equals(got, exps)) { sampleBad++; if (firstBadPos < 0) firstBadPos = off }
            p(70 + k * 30 / list.size, "Vérification d'échantillons… ${k * 100 / list.size} %")
        }
        wipeStart(dev)
        p(100, "Test terminé")

        return if (lowBad == 0 && sampleBad == 0) {
            Result(true, "Aucun problème détecté sur ${gb(total)} (test rapide, ${list.size} échantillons).\n\nCe test peut rater certaines fausses clés : le test complet est plus fiable.\nLa clé est maintenant vide : formate-la avant de l'utiliser.")
        } else {
            Result(false,
                "Fausse capacité probable !\n\nCapacité annoncée : ${gb(total)}\n" +
                    (if (lowBad > 0) "Les données écrites loin sur la clé écrasent son début : sa vraie taille est plus petite que celle annoncée.\n" else "") +
                    (if (sampleBad > 0) "$sampleBad échantillon(s) sur ${list.size} n'ont pas été conservés (le premier vers ${gb(firstBadPos)}).\n" else "") +
                    "\nLance le test complet pour connaître la vraie capacité. Formate la clé avant de l'utiliser.")
        }
    }
}
