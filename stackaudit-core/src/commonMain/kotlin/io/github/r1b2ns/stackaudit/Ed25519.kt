package io.github.r1b2ns.stackaudit

internal object Sha512 {
    private val K =
        longArrayOf(
            0x428a2f98d728ae22UL.toLong(), 0x7137449123ef65cdUL.toLong(), 0xb5c0fbcfec4d3b2fUL.toLong(), 0xe9b5dba58189dbbcUL.toLong(),
            0x3956c25bf348b538UL.toLong(), 0x59f111f1b605d019UL.toLong(), 0x923f82a4af194f9bUL.toLong(), 0xab1c5ed5da6d8118UL.toLong(),
            0xd807aa98a3030242UL.toLong(), 0x12835b0145706fbeUL.toLong(), 0x243185be4ee4b28cUL.toLong(), 0x550c7dc3d5ffb4e2UL.toLong(),
            0x72be5d74f27b896fUL.toLong(), 0x80deb1fe3b1696b1UL.toLong(), 0x9bdc06a725c71235UL.toLong(), 0xc19bf174cf692694UL.toLong(),
            0xe49b69c19ef14ad2UL.toLong(), 0xefbe4786384f25e3UL.toLong(), 0x0fc19dc68b8cd5b5UL.toLong(), 0x240ca1cc77ac9c65UL.toLong(),
            0x2de92c6f592b0275UL.toLong(), 0x4a7484aa6ea6e483UL.toLong(), 0x5cb0a9dcbd41fbd4UL.toLong(), 0x76f988da831153b5UL.toLong(),
            0x983e5152ee66dfabUL.toLong(), 0xa831c66d2db43210UL.toLong(), 0xb00327c898fb213fUL.toLong(), 0xbf597fc7beef0ee4UL.toLong(),
            0xc6e00bf33da88fc2UL.toLong(), 0xd5a79147930aa725UL.toLong(), 0x06ca6351e003826fUL.toLong(), 0x142929670a0e6e70UL.toLong(),
            0x27b70a8546d22ffcUL.toLong(), 0x2e1b21385c26c926UL.toLong(), 0x4d2c6dfc5ac42aedUL.toLong(), 0x53380d139d95b3dfUL.toLong(),
            0x650a73548baf63deUL.toLong(), 0x766a0abb3c77b2a8UL.toLong(), 0x81c2c92e47edaee6UL.toLong(), 0x92722c851482353bUL.toLong(),
            0xa2bfe8a14cf10364UL.toLong(), 0xa81a664bbc423001UL.toLong(), 0xc24b8b70d0f89791UL.toLong(), 0xc76c51a30654be30UL.toLong(),
            0xd192e819d6ef5218UL.toLong(), 0xd69906245565a910UL.toLong(), 0xf40e35855771202aUL.toLong(), 0x106aa07032bbd1b8UL.toLong(),
            0x19a4c116b8d2d0c8UL.toLong(), 0x1e376c085141ab53UL.toLong(), 0x2748774cdf8eeb99UL.toLong(), 0x34b0bcb5e19b48a8UL.toLong(),
            0x391c0cb3c5c95a63UL.toLong(), 0x4ed8aa4ae3418acbUL.toLong(), 0x5b9cca4f7763e373UL.toLong(), 0x682e6ff3d6b2b8a3UL.toLong(),
            0x748f82ee5defb2fcUL.toLong(), 0x78a5636f43172f60UL.toLong(), 0x84c87814a1f0ab72UL.toLong(), 0x8cc702081a6439ecUL.toLong(),
            0x90befffa23631e28UL.toLong(), 0xa4506cebde82bde9UL.toLong(), 0xbef9a3f7b2c67915UL.toLong(), 0xc67178f2e372532bUL.toLong(),
            0xca273eceea26619cUL.toLong(), 0xd186b8c721c0c207UL.toLong(), 0xeada7dd6cde0eb1eUL.toLong(), 0xf57d4f7fee6ed178UL.toLong(),
            0x06f067aa72176fbaUL.toLong(), 0x0a637dc5a2c898a6UL.toLong(), 0x113f9804bef90daeUL.toLong(), 0x1b710b35131c471bUL.toLong(),
            0x28db77f523047d84UL.toLong(), 0x32caab7b40c72493UL.toLong(), 0x3c9ebe0a15c9bebcUL.toLong(), 0x431d67c49c100d4cUL.toLong(),
            0x4cc5d4becb3e42b6UL.toLong(), 0x597f299cfc657e2aUL.toLong(), 0x5fcb6fab3ad6faecUL.toLong(), 0x6c44198c4a475817UL.toLong(),
        )

    fun digest(message: ByteArray): ByteArray {
        val h =
            longArrayOf(
                0x6a09e667f3bcc908UL.toLong(), 0xbb67ae8584caa73bUL.toLong(), 0x3c6ef372fe94f82bUL.toLong(), 0xa54ff53a5f1d36f1UL.toLong(),
                0x510e527fade682d1UL.toLong(), 0x9b05688c2b3e6c1fUL.toLong(), 0x1f83d9abfb41bd6bUL.toLong(), 0x5be0cd19137e2179UL.toLong(),
            )
        val padded = ((message.size + 17 + 127) / 128) * 128
        val data = message.copyOf(padded)
        data[message.size] = 0x80.toByte()
        val bits = message.size.toLong() * 8
        for (i in 0 until 8) data[padded - 1 - i] = (bits ushr (8 * i)).toByte()
        val w = LongArray(80)
        for (block in 0 until padded / 128) {
            for (t in 0 until 16) {
                var v = 0L
                for (b in 0 until 8) v = (v shl 8) or (data[block * 128 + t * 8 + b].toLong() and 0xff)
                w[t] = v
            }
            for (t in 16 until 80) {
                val s0 = w[t - 15].rotateRight(1) xor w[t - 15].rotateRight(8) xor (w[t - 15] ushr 7)
                val s1 = w[t - 2].rotateRight(19) xor w[t - 2].rotateRight(61) xor (w[t - 2] ushr 6)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]
            for (t in 0 until 80) {
                val s1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g
                g = f
                f = e
                e = d + t1
                d = c
                c = b
                b = a
                a = t1 + t2
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
        }
        val out = ByteArray(64)
        for (i in 0 until 8) for (b in 0 until 8) out[i * 8 + b] = (h[i] ushr (56 - 8 * b)).toByte()
        return out
    }
}

internal object Ed25519 {
    private fun gf(vararg init: Long): LongArray = LongArray(16).also { init.copyInto(it) }

    private val gf0 = gf()
    private val gf1 = gf(1)
    private val D = gf(0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070, 0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203)
    private val D2 = gf(0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0, 0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406)
    private val X = gf(0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c, 0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169)
    private val Y = gf(0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666)
    private val I = gf(0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43, 0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83)
    private val L =
        longArrayOf(
            0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
        )

    fun verify(
        signature: ByteArray,
        message: ByteArray,
        publicKey: ByteArray,
    ): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        val q = Array(4) { gf() }
        if (!unpackneg(q, publicKey)) return false
        val hashInput = ByteArray(64 + message.size)
        signature.copyInto(hashInput, 0, 0, 32)
        publicKey.copyInto(hashInput, 32)
        message.copyInto(hashInput, 64)
        val h = Sha512.digest(hashInput)
        reduce(h)
        val p = Array(4) { gf() }
        scalarmult(p, q, h)
        val base = Array(4) { gf() }
        scalarbase(base, signature.copyOfRange(32, 64))
        add(p, base)
        val t = ByteArray(32)
        pack(t, p)
        var diff = 0
        for (i in 0 until 32) diff = diff or (t[i].toInt() xor signature[i].toInt())
        return diff == 0
    }

    private fun set(
        r: LongArray,
        a: LongArray,
    ) = a.copyInto(r)

    private fun car(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    private fun sel(
        p: LongArray,
        q: LongArray,
        b: Int,
    ) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack25519(
        o: ByteArray,
        n: LongArray,
    ) {
        val m = gf()
        val t = n.copyOf()
        car(t)
        car(t)
        car(t)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel(t, m, 1 - b)
        }
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
    }

    private fun neq(
        a: LongArray,
        b: LongArray,
    ): Boolean {
        val c = ByteArray(32)
        val d = ByteArray(32)
        pack25519(c, a)
        pack25519(d, b)
        return !c.contentEquals(d)
    }

    private fun par(a: LongArray): Int {
        val d = ByteArray(32)
        pack25519(d, a)
        return d[0].toInt() and 1
    }

    private fun unpack25519(
        o: LongArray,
        n: ByteArray,
    ) {
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
    }

    private fun add25519(
        o: LongArray,
        a: LongArray,
        b: LongArray,
    ) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    private fun sub(
        o: LongArray,
        a: LongArray,
        b: LongArray,
    ) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    private fun mul(
        o: LongArray,
        a: LongArray,
        b: LongArray,
    ) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        car(o)
        car(o)
    }

    private fun sq(
        o: LongArray,
        a: LongArray,
    ) = mul(o, a, a)

    private fun inv(
        o: LongArray,
        i: LongArray,
    ) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            sq(c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        set(o, c)
    }

    private fun pow2523(
        o: LongArray,
        i: LongArray,
    ) {
        val c = i.copyOf()
        for (a in 250 downTo 0) {
            sq(c, c)
            if (a != 1) mul(c, c, i)
        }
        set(o, c)
    }

    private fun add(
        p: Array<LongArray>,
        q: Array<LongArray>,
    ) {
        val a = gf()
        val b = gf()
        val c = gf()
        val d = gf()
        val t = gf()
        val e = gf()
        val f = gf()
        val g = gf()
        val h = gf()
        sub(a, p[1], p[0])
        sub(t, q[1], q[0])
        mul(a, a, t)
        add25519(b, p[0], p[1])
        add25519(t, q[0], q[1])
        mul(b, b, t)
        mul(c, p[3], q[3])
        mul(c, c, D2)
        mul(d, p[2], q[2])
        add25519(d, d, d)
        sub(e, b, a)
        sub(f, d, c)
        add25519(g, d, c)
        add25519(h, b, a)
        mul(p[0], e, f)
        mul(p[1], h, g)
        mul(p[2], g, f)
        mul(p[3], e, h)
    }

    private fun cswap(
        p: Array<LongArray>,
        q: Array<LongArray>,
        b: Int,
    ) {
        for (i in 0 until 4) sel(p[i], q[i], b)
    }

    private fun pack(
        r: ByteArray,
        p: Array<LongArray>,
    ) {
        val tx = gf()
        val ty = gf()
        val zi = gf()
        inv(zi, p[2])
        mul(tx, p[0], zi)
        mul(ty, p[1], zi)
        pack25519(r, ty)
        r[31] = (r[31].toInt() xor (par(tx) shl 7)).toByte()
    }

    private fun scalarmult(
        p: Array<LongArray>,
        q: Array<LongArray>,
        s: ByteArray,
    ) {
        set(p[0], gf0)
        set(p[1], gf1)
        set(p[2], gf1)
        set(p[3], gf0)
        for (i in 255 downTo 0) {
            val b = (s[i / 8].toInt() shr (i and 7)) and 1
            cswap(p, q, b)
            add(q, p)
            add(p, p)
            cswap(p, q, b)
        }
    }

    private fun scalarbase(
        p: Array<LongArray>,
        s: ByteArray,
    ) {
        val q = Array(4) { gf() }
        set(q[0], X)
        set(q[1], Y)
        set(q[2], gf1)
        mul(q[3], X, Y)
        scalarmult(p, q, s)
    }

    private fun modL(
        r: ByteArray,
        x: LongArray,
    ) {
        var carry: Long
        for (i in 63 downTo 32) {
            carry = 0
            var j = i - 32
            while (j < i - 12) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)]
                carry = (x[j] + 128) shr 8
                x[j] -= carry shl 8
                j++
            }
            x[j] += carry
            x[i] = 0
        }
        carry = 0
        for (j in 0 until 32) {
            x[j] += carry - (x[31] shr 4) * L[j]
            carry = x[j] shr 8
            x[j] = x[j] and 255
        }
        for (j in 0 until 32) x[j] -= carry * L[j]
        for (i in 0 until 32) {
            x[i + 1] += x[i] shr 8
            r[i] = (x[i] and 255).toByte()
        }
    }

    private fun reduce(r: ByteArray) {
        val x = LongArray(64) { r[it].toLong() and 0xff }
        r.fill(0)
        modL(r, x)
    }

    private fun unpackneg(
        r: Array<LongArray>,
        p: ByteArray,
    ): Boolean {
        val t = gf()
        val chk = gf()
        val num = gf()
        val den = gf()
        val den2 = gf()
        val den4 = gf()
        val den6 = gf()
        set(r[2], gf1)
        unpack25519(r[1], p)
        sq(num, r[1])
        mul(den, num, D)
        sub(num, num, r[2])
        add25519(den, r[2], den)
        sq(den2, den)
        sq(den4, den2)
        mul(den6, den4, den2)
        mul(t, den6, num)
        mul(t, t, den)
        pow2523(t, t)
        mul(t, t, num)
        mul(t, t, den)
        mul(t, t, den)
        mul(r[0], t, den)
        sq(chk, r[0])
        mul(chk, chk, den)
        if (neq(chk, num)) mul(r[0], r[0], I)
        sq(chk, r[0])
        mul(chk, chk, den)
        if (neq(chk, num)) return false
        if (par(r[0]) == ((p[31].toInt() and 0xff) shr 7)) sub(r[0], gf0, r[0])
        mul(r[3], r[0], r[1])
        return true
    }
}
