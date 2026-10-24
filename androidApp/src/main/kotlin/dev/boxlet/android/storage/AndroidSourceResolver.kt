package dev.boxlet.android.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import dev.boxlet.core.protocol.bp2.Bp2Entry
import dev.boxlet.core.protocol.bp2.Bp2SourceRef
import dev.boxlet.core.session.bp2.Bp2FileSourceResolver
import dev.boxlet.core.session.bp2.Bp2SourceResolver
import dev.boxlet.core.session.bp2.Bp2SourceUnavailable
import okio.Source
import okio.source

/** Only previously selected documents with a retained read grant can be rebuilt after restart. */
class AndroidSourceResolver(private val context: Context) : Bp2SourceResolver {
    override fun open(reference: Bp2SourceRef, entry: Bp2Entry): Source {
        if (reference.kind == "file") return Bp2FileSourceResolver.open(reference, entry)
        reference.validate()
        val grant = reference.grant ?: throw Bp2SourceUnavailable()
        val resolver = context.contentResolver
        val uri = Uri.parse(reference.locator)
        if (resolver.persistedUriPermissions.none { it.isReadPermission && it.uri.toString() == grant } ||
            context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)
            throw Bp2SourceUnavailable()
        return try { resolver.openInputStream(uri)?.buffered(1024 * 1024)?.source() ?: throw Bp2SourceUnavailable() }
        catch (error: SecurityException) { throw Bp2SourceUnavailable(error) }
        catch (error: java.io.FileNotFoundException) { throw Bp2SourceUnavailable(error) }
    }
}
