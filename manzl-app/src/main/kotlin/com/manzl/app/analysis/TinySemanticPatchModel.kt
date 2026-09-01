package com.manzl.app.analysis

import java.util.Base64
import kotlin.math.exp

/**
 * Tiny fully-local neural patch classifier trained only on procedurally generated architectural
 * symbols. It does not alter geometry and has no runtime dependency beyond Kotlin/Java.
 *
 * Classes: OTHER, DOOR, WINDOW, STAIR.
 *
 * Architecture: 256 -> 16 ReLU -> 4 softmax. Weights are symmetric int8 quantized per layer.
 */
internal object TinySemanticPatchModel {

    enum class PatchClass { OTHER, DOOR, WINDOW, STAIR }

    data class Prediction(
        val label: PatchClass,
        val confidence: Float,
        val margin: Float,
        val probabilities: FloatArray,
    )

    fun predict(ink: FloatArray): Prediction {
        require(ink.size == INPUT_SIZE) { "Expected $INPUT_SIZE input values" }

        val hidden = FloatArray(HIDDEN_SIZE)
        for (h in 0 until HIDDEN_SIZE) {
            var sum = BIAS_1[h]
            val base = h * INPUT_SIZE
            for (i in 0 until INPUT_SIZE) {
                sum += ink[i].coerceIn(0f, 1f) * WEIGHTS_1[base + i].toInt() * SCALE_1
            }
            hidden[h] = if (sum > 0f) sum else 0f
        }

        val logits = FloatArray(OUTPUT_SIZE)
        for (o in 0 until OUTPUT_SIZE) {
            var sum = BIAS_2[o]
            val base = o * HIDDEN_SIZE
            for (h in 0 until HIDDEN_SIZE) {
                sum += hidden[h] * WEIGHTS_2[base + h].toInt() * SCALE_2
            }
            logits[o] = sum
        }

        val maxLogit = logits.maxOrNull() ?: 0f
        val exps = FloatArray(OUTPUT_SIZE)
        var denominator = 0f
        for (i in logits.indices) {
            val value = exp((logits[i] - maxLogit).toDouble()).toFloat()
            exps[i] = value
            denominator += value
        }
        val probabilities = FloatArray(OUTPUT_SIZE) { index ->
            if (denominator <= 0f) 0f else exps[index] / denominator
        }

        var bestIndex = 0
        var second = -1f
        for (i in 1 until probabilities.size) {
            if (probabilities[i] > probabilities[bestIndex]) {
                second = probabilities[bestIndex]
                bestIndex = i
            } else if (probabilities[i] > second) {
                second = probabilities[i]
            }
        }
        if (second < 0f) second = 0f
        val best = probabilities[bestIndex]
        return Prediction(
            label = PatchClass.entries[bestIndex],
            confidence = best,
            margin = (best - second).coerceIn(0f, 1f),
            probabilities = probabilities,
        )
    }

    private val WEIGHTS_1: ByteArray by lazy {
        Base64.getDecoder().decode(
            "2Q3Y5PsLExYcFxYqGzI9+Q3z3e3+AgIsMyY/KxUSBfwT+trp8vMIODEvNhwXDe7DIAb1Bwv/DzcvGRslEx/0vyIhCRgOFAwbERMaLh0Z+tYeQDkoCP7369/kA/wIDwACFyo7KAUNFArq8d++1gEJFB84Rx76CTAmGQ7evLncFhoYEDcb7womLSMT7dvT4SISDSEP387pGCI6LfzEyNolIwEB2b+wy/oCAg/1ws3d/TXuAe/JuMDo/gr74NTL1RBN4//mz8Tb7BUcCOLP1cPeC+vu2szE2BQ5IwIB19S66yQEDePi7O4OJzcYCfXvv9cOGzUwJS8jLw8bHgYC7OD/IwPu/QsKDP8CChP95u/VyvjN7e32BvQQLQj+9OvY290NwNPb+hADCyksIxHf19Xd89HP5vsJAQUmGBkN/PgL+Q3I7OX18gUSFgINAfMMDAED/QH18vAAIRwPHRcBCw7/HggVKA/5Dx0V/Q4oEBEVEvTxGBsK/wIBBvsWNw8UHxXu8SslFxcYGAwGBx4WGh4RBgUdDw0MHjgdAicgCRsaCA0NCP7t7QEcFwYJEuTs/+0L9wQG/QDqAg4M/vz27uTn0PL3/wIG/xUmFAkABunf2NQC5+fZ4O0aJScFH/bl28XD7NrT2d32ECMjHxz87+To0gLE2tPE8wMDBfUTHQAJ6f42PThFCgkhzvakyRskIB47DB8xLu//CQz88/kMGjUiPyYnGyMUH/7uAfUTHhMcFyf9CgAPFyj/5/kKGB4ZFAtfEOb4CR0l++rzCBwmMiL3CsTy8Pn9HAXl2+L4IiYC7bvW+NXL7fT93ODJ9fj8/u/0++/mz8rwCQcJ4fL47Oz+BAIG6OTp/ufz8tjj9AP4+vP8/uMBCxf84e/08BkM/O7W9vcXEhIlEPXq9gMpLRQB9yMABwAZHAfm9tn2DQj7/gI/EA8JCRT219bQ/An5/xMYLgn///n769Dq5OEHDQoMHlAcIyDkEwD5B+HeBhEQDTIqIysc9ALf1iTRyUZRQD1F+BA5PlI8Lv7i9PnRAv4QOrHR1fIsGhQR7OPjvszn5A3WtNTjEComGfP35bPAvc3j+9fW7QsbLCTs6eHGytzR9B/w7vftGjEp7AP14+71BR4OFB4d9jJMKQ8NGf35BAouHw8tJRc1MP3vCxwB9gcUKgL/GBcHNxDO3v8gDRMjAgv0/fwOBicV3s7xIvz8Ey4ZFRsE8+MRC9fmBhQDAAoeFBAkDvHcAxDi5wMb5fojCRgF9uTn4fEN9N/pEvbw4Oru/9rW1swHI/bn5PAA8OXI5gnh3OrY7hEA8vcD4urs1OQD1ADc2fT//xsOAtLJ/eHrMPkUCCEQHwPi8vnx9yADSN399N4GLQEeyc0EycTh0Bvr7t0DIxH2FfkUyeTy39oKANPiKQ0X+g4BB8Gt3OLN8tGi0RYhEvfwB/+otL7H8wbvxcP0+QDo1/jenKW11NHq6bPG4vP13crq5YGEj62ut+L3/iMeJfr0HBDBvdP5/tG3AwYsNT4gCSIa1MXp9QHQ5BEtJzgn++r68szG4e8LD/XvESAuLRbzDQXG2t3sBgsBACM7NUY+GSYu9AckLCMTBxUlKCU6MyMWFeoELDItA8UIDjYvLAv+Ch3V+S8/ODMr5fT/BR0M7wgR5OIAIhYZ+6e85Pb38Pru0tHFuOL//iG/0+ftIgIxzsjM0+wQ/AUlGiYSGN7z7+fj3O/r4PkQ6BAg/fTwDh0XGgP2ABQRIvYaHg4B7wYFBv/t+ucLDiIeJxgKGQH4Bwv6BwL9Cw1JHB8lFxgcAxIVJg4I/f7/IgssJzQzKicUBRkGEQkGDxPN9/sWIBMK6+Ln7+fo7e/wxvTx9gMRBv7v0OH23+YFxsbv7vEFAwry++fo7O38BcDW4On57gDh8v0GBvr44unH+PARAxUWAQwULykjHhMT6z8WGh0B+wADFxYcHC0ZGRhSMAT15+8JAhQMChseKSoHQRbz7+nZ7wEBAgkQHjAuBB8H+vPS7wEJDgz6AAAvIxIP8+Pd4dLb3O/+7xUnGgn68Br7+eTRCg4J++b8AhEjGSAMA/7l5d/X5+Hi3+YACwsXCu/o7OPZzdfe5e7j/P39HQcB+fz87uDv9PP67f4EFxsgHQkNGhP+/gL9AwcXBhAIIREWDQ4GGxH4DRwiIhgNABD8+wb/BQwT/fwFGR0RNOLY0tvf3uzk+Pv88+zh6OfZ1Mfg3u7t7+X+5+fs5+DTt+rZ6urb0+nc6drd4N3kw9YVEyId8fwKCvn6CBYWDfANHCUrKyccBiAgHSQeICQuBhcdGxwkDgIQHCAbER4ZLNMQFRQTGQMMBw4IBg8PIjbzIhkQGQIJCAMAAwoIFSIp/zAqJDLh9P0lDuTj9+wXIu4aAf/71jMB1A0LAfoSxBH057Le7e/e+ODw2+Lw07/89QT24gEJuvTvB/XxD+/c5hknHOcHCMkFFx79ICAJ++8qPB0iAPrSBxUXAyQtFhEQFR8oIiUJ2v4yLBsiKxUDIxAICig1Dt0EEyojPkYxCgMTB/vp/wPS9xIRBy0xKgEQJgbh0+zfyAEoHPEVHREGIBMP3c/r2sEBMx/9HioI6P/u3cnP3Ne8+Swa6w8OBd7I0bKfpsi7pMcD9MPe5MTE2NmosqDY8KPZA//KEAHVtfUa4M/a+gvOBhQW6RsK37bp6esG4vwD+QkC/wUFIuCtGxIEFhft9SsFw/4lFNYCAv4g9BIq8+QjKyI6LDhDIQFD+Nzd+9PM/xoIAwoH/vD8Kd3Iwefdx+IXGxkOEhQA3xruz9LUzb/cCicgFBAWD/0o8MTAvdG80gUEDg8ECBsKDQPfw8nLwOH7JSMcC//66gYB29bi0sn9+gIRKgb94eT1C/L+/PXmDQcBDCUEG/7f8fr+GTMgCBcR//IL8PDkwvANABoqJBYkMAT/HOXY6doTDgAMFhMAIkIpEAzVvr7RGiAXGAAF9A4iGgjkvs7S9f0Y/QT5AAIIIyT889LL8+r9IPHv/gj1Ax4SAADt4+cB8yPw5f344+H+9/AADQPv1ew/5A33BdYPHBAiOiE7Kj45JyklKDsfEdXCFSUtIyYwOQ4nJhIUGwT58i0gFAUWGS0sMxwSGyH69vQtHREKCg8WGQUZCyYg8+XnFhkM+wL+LxP8AP8hGe3a4PwiFgj45wXq/xEPGhvi2N4FEhAUBPXj/gkMGhH/28fc/wQQBgcOHPL1/ggC5MTY8AP89P/0AO/9AAkP/OnW1u4L+Ozw/wL0EgsPHgkF7eveABAPDQUWBfP0EhMVFPTf0QITGBYOAAAJ8PsSDhD55tgSExMQD/gTLA72CRMl8OjoGx4hARANHRQH9xMaIPLk7ywUFhUJJik1GxodESD53+kYDhMEByEdKBM1KTQP7cnNKhszIyNDQLnh7djM88fzKAgG/gcQKL0YERIWCSL76QcMGB0HBBHYCQANERYNEvwECBcJEAYMxgP8Awn/EhoSEg8pIw8VEsbwCwH6+vwaJxcYCxsUGhbWBwP88fL4GhsbGCAgHhAT+vv7Bfrk8wYQ/ycfJiAXAuX/9+76BwILB+oFIisPC+b3AvcJCP8TFBMCBB0lG/70++gQGhghDh0NDh3+/wX9++TzLgoaEPsSCAsM+e0M+QDt0f4TCQ0FEwIFFRD8ERocBNDi9gP9BA8T8wkc7wsBFhn17/Hn5efx89XkAvYHDCZC2s/m1eDExsXC1gH9CxotMNKwwsu93cXW8OTUsa7Fw78rHyMvLyQ0HwoNEgr7+/1GCQ0J/QwCEBoTA/bk4+7oMhoG/QABDwkZEwj68OT38xgW+wX5EwwKDAYK8OP39PIvIP368QYPAfz66ffx8e/hIw4KDAcOAvr14+rr6//z9A4bCw4ZA/rz4+/p/vEHCAg7JhQUCgvk3N30+QMDDxAXJyAZHBf739/d+PcCBQwLFRg4FgsE+/Hh4+f7/woLDRU0IP0KCwn37efhBgEGEhsSKxnw/QEJ+AP/BAcFDRgJ/SM1+/Ly6woFERMNABEOEwgUGujk6/YIDQoQD/wC9wcXJyb67/PnAhcRCPnx/O0EEBI1/xP/KAMgFP0ZDhwlMEBT6u7Y4fPsEQb39AEeCx8+yh4qGCgnKQv4Ag0FKQgsN+IcB/cFHx4H+hMP+xsLGS3yDg767QsH9vQjAeQNBhYi9f8fEg4WMB8cIQvuEwQYKP4DCQ4KICYLHjAN9hIHJCL2/hQVCiQrFxU2JwQLChwHDwoHBxwqNhoHMzYTHwYPCQMMAPQEEjMRBhgf/hD/BQEBDgYBBxIhDRAmF/n+/gsH+v8dFRstIhEfPCAGERIMBvT5JxkNFAr8/xkN8QsMEhYCCycNChQT7fgM+OT29/4Y/tErGx4pFwkABQDuCAsFJAnVMx0sOyQRCv8WChcqKyws0j8WICIGEwAF/PzpE9zmvwnl8woJ8eDx7unttbyosLzG9QUR/Q4YNBsiD/vl4tfquef2DBIbERMoHQTw7eP4BNMKAxQJCg0dDw35+PwMCh3UEBYUBwUGIhgT+QIKFBwi7g8TDgEC/RMWC/b8/AcJAQgjHxcSGBAXDR4RDwECDv3uMyMODBwSGQchDgkDGSnuziIQBgcNCRYLGBEICR4q5+n2+/Xz9/sFFhUM/gsKDuf18/v17/jwAQQQ9/H6EQPl9QELCP7z9QoRA///BwIFxg4M+wP69/0NGvj4EAQCAMEG8/Xr6fgLFBURDBMM9umw7u/j6enuAx06JA8DBQb4xePHxdCn6+j8AN0UCAkK0cXJ4ejPz/Th8h4TA+zzAP3IDfrzDQANBfX6AAYGBO774gH4Agv+BhX/AwUZAAP5/N758/cC/AgeFQkSHhgR/wHE6QwEDBAKIiEYFhcYDQ8M5gL+/g4ACi8iEiAYERQXE/0C8/0ACQcXKRAfIBUWC/fr+fT0AAsCFBIOGCgdBxLx+fzpAA0LFw4bECQzFw3/7APv/hkYFggkGxkoMRMUDvXu/A3+Awj6GhcOFxkJC/b49NkJCQ0SCBkYExUjHAsFEfTC9gwNExAZKwEVLA8fCAX34QoMBA4LFCMMDBgICfz2Bdz6APgVAhUHCAQeAxYJAgrYEPQM6xcJBw4W/NPc39HLIvcA/xH99Pfg7Oq6vLnL6OkGFAIEAxIkFxgC5ePf5ATc//4RGRULFikZ7eTd4u4E3wcJGBMMBBYQCeTq9f0BK9QIFh0KCQ0TEAb5//YB/BXkBAoREAQMDgUHAPX6BQIJDSkgEBEnGggKEAYC+f0TEOk3KxYMIg0ODxgUCQYVJdrmMB0LDg0NEBoZFgkVKC7t7gHx8+n/8fIUHBATHBMV8h31++nxAPn/ExMM/wogDQAF/QH98OzyAx0K/A0XBAXXCQH5+e74/QAXCP4VCv0P2/zk5Obk8g0NFQoNEwH86cvh9d/j3O8DGzUjEPj7APzV89TCyLnp+/7h3AgGFhH16w=="
        )
    }

    private val WEIGHTS_2: ByteArray by lazy {
        Base64.getDecoder().decode(
            "9CNYStM/I/9SPb9J6BHIGkm5/Jm72jWrgRcnFBy4Eq4GH5Qwa7K9Wh3aBuPR/xr1pxmxuskRvLnMzQq4LS4SHw=="
        )
    }

    private val BIAS_1 = floatArrayOf(
        0.61259544f,-0.60717422f,0.78931457f,0.85689676f,0.18807192f,-0.72525090f,0.66565573f,0.56413013f,0.94408065f,0.67038655f,-0.09343705f,0.64789033f,-0.43103969f,-0.92411429f,-0.15562801f,-0.81857336f
    )

    private val BIAS_2 = floatArrayOf(
        0.36836475f,0.49434394f,0.08324977f,-0.34746179f
    )

    private const val INPUT_SIZE = 256
    private const val HIDDEN_SIZE = 16
    private const val OUTPUT_SIZE = 4
    private const val SCALE_1 = 0.0072322288f
    private const val SCALE_2 = 0.0111251129f
}
