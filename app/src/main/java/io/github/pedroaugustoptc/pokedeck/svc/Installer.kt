package io.github.pedroaugustoptc.pokedeck.svc

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import io.github.pedroaugustoptc.pokedeck.BuildConfig
import io.github.pedroaugustoptc.pokedeck.core.Log
import io.github.pedroaugustoptc.pokedeck.core.Release
import io.github.pedroaugustoptc.pokedeck.core.Updater
import io.github.pedroaugustoptc.pokedeck.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Atualização pelo GitHub (sem loja): lê a release mais nova, baixa o PokeDeck.apk, confere o SHA-256 publicado pelo
 * GitHub e entrega ao instalador do Android. O APK novo precisa ter a mesma assinatura (o CI usa sempre a mesma chave).
 */
object Installer {
    private const val UA = "PokeDeck-updater" // o GitHub exige um User-Agent

    /** A release mais nova, se for mais nova que este app; null se não há ou não deu para saber. */
    suspend fun check(): Release? = try {
        val res = Http.send(Http.get(Updater.LATEST_URL).header("Accept", "application/vnd.github+json"), ua = UA)
        if (res.ok) Updater.parse(res.body)?.takeIf { Updater.isNewer(BuildConfig.VERSION_NAME, it) } else null
    } catch (e: IOException) {
        null
    }

    /** Baixa para o cache conferindo o SHA-256; [progress] recebe 0..100. Erro vira IOException com a explicação. */
    suspend fun download(context: Context, r: Release, progress: (Int) -> Unit): File = runInterruptible(Dispatchers.IO) {
        val file = File(context.cacheDir, "update.apk")
        val req = Http.get(r.apkUrl).header("User-Agent", UA).build()
        Http.client.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("download: erro ${res.code}")
            val body = res.body ?: throw IOException("download vazio")
            val total = body.contentLength()
            val sha = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var last = -1
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) {
                            last = pct
                            progress(pct)
                        }
                    }
                }
            }
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (hex != r.sha256) {
                file.delete()
                throw IOException("o arquivo baixado não confere com a release (SHA-256)")
            }
        }
        file
    }

    /** Entrega ao instalador do Android; a confirmação chega pelo [UpdateReceiver]. */
    fun install(context: Context, apk: File) {
        Deck.accounts.filter { it.game != null }.forEach { Web.flush(it.profile) } // logins no disco antes de trocar o app
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("PokeDeck.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val status = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE) // o instalador preenche o resultado
            s.commit(status.intentSender)
        }
    }
}

/** O resultado da instalação: pede a confirmação do usuário ou registra o erro. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            PackageInstaller.STATUS_SUCCESS -> Log.write(null, "atualização instalada")
            else -> Log.write(null, "atualização falhou ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
