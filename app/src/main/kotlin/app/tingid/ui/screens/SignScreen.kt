// A site's signing request (TOCS-1.md): fetched from the address in its code,
// shown with the facts the app vouches for apart from what the site wrote, and
// answered with the user's signature or a refusal.
package app.tingid.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import app.tingid.chain.Offchain
import app.tingid.chain.SignRequest
import app.tingid.chain.hex
import app.tingid.chain.nowSeconds
import app.tingid.chain.relativeTime
import app.tingid.state.SignatureKind
import app.tingid.state.SignatureNote
import app.tingid.state.SignatureOutcome
import app.tingid.state.User
import app.tingid.state.possibleSigners
import app.tingid.state.signerProblem
import app.tingid.text.describeSignRequest
import app.tingid.text.siteName
import app.tingid.ui.Avatar
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Body
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.Field
import app.tingid.ui.HoldWhileBusy
import app.tingid.ui.IconCircle
import app.tingid.ui.Loading
import app.tingid.ui.LocalActivity
import app.tingid.ui.LocalNav
import app.tingid.ui.LocalServices
import app.tingid.ui.Pill
import app.tingid.ui.Radius
import app.tingid.ui.Screen
import app.tingid.ui.SignRoute
import app.tingid.ui.Spacing
import app.tingid.ui.Sub
import app.tingid.ui.Tone
import app.tingid.ui.Type
import app.tingid.ui.WalletTab
import app.tingid.ui.confirmOwner
import app.tingid.ui.manropeStyle
import app.tingid.ui.openFile
import app.tingid.ui.sentence
import app.tingid.ui.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed class Step {
    data object Enter : Step()
    class Loading(val url: String) : Step()
    class Failed(val message: String) : Step()
    class Review(val request: SignRequest) : Step()
    class Sending(val request: SignRequest) : Step()
    class Done(val request: SignRequest, val signed: Boolean, val message: String) : Step()
}

/** Each code opens its own review: a new link while one is open starts afresh. */
@Composable
fun SignScreen(route: SignRoute) {
    key(route.code, route.at) { SignFlow(route.code ?: "") }
}

@Composable
private fun SignFlow(linked: String) {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val w by services.workspace.state.collectAsState()
    val users = services.store.data.collectAsState().value.users
    var code by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(Offchain.parseSignCode(linked)?.let { Step.Loading(it) } ?: Step.Enter) }
    var error by remember { mutableStateOf<String?>(null) }
    val chainHex = w.info?.chainHex
    // Answering cannot be left half-way: the site would not know, and neither would the person.
    HoldWhileBusy(step is Step.Sending)

    // Fetched with no cookies and no redirects (core: Signing), and checked before anything shows.
    LaunchedEffect(step) {
        val loading = step as? Step.Loading ?: return@LaunchedEffect
        step = try {
            Step.Review(services.signing.fetchSignRequest(loading.url))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Step.Failed(e.message ?: "the site's request could not be read")
        }
    }

    fun open() {
        val url = Offchain.parseSignCode(code)
        if (url == null) {
            error = "That is not a signing code from a secure site."
            return
        }
        error = null
        step = Step.Loading(url)
    }

    fun note(request: SignRequest, outcome: SignatureOutcome, message: String?) {
        val user = w.user ?: return
        val kind = when (request.request.request) {
            is Offchain.Request.SignIn -> SignatureKind.SignIn
            is Offchain.Request.Grant -> SignatureKind.Grant
            is Offchain.Request.Statement -> SignatureKind.Statement
        }
        services.store.addSignature(SignatureNote(nowSeconds(), user.id, request.origin, kind, request.request.title, outcome, message))
    }

    fun sign(request: SignRequest) {
        val now = w
        val user = now.user ?: return
        val info = now.info ?: return
        error = null
        scope.launch {
            // The phone's owner first, as for a payment. The site's title is quoted: it is the site's.
            try {
                if (!confirmOwner(activity, services.store, "Sign for ${request.origin}", "“${request.request.title}”")) return@launch
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "the phone did not confirm it is you"
                return@launch
            }
            step = Step.Sending(request)
            try {
                val message = services.signing.signAndAnswer(request, user, info.canonical, now.keys, now.identity)
                note(request, SignatureOutcome.Signed, message)
                step = Step.Done(request, true, message)
                // On a phone, a site that asked from its browser page gets the user back.
                request.returnTo?.let { openSite(activity, it) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val message = e.message ?: "the answer could not be sent"
                note(request, SignatureOutcome.Failed, message)
                error = message
                step = Step.Review(request)
            }
        }
    }

    fun decline(request: SignRequest) {
        step = Step.Sending(request)
        scope.launch {
            services.signing.declineRequest(request)
            note(request, SignatureOutcome.Declined, null)
            step = Step.Done(request, false, "The site was told you declined.")
        }
    }

    val p = t
    Screen(title = "Signing request") {
        when (val s = step) {
            Step.Enter -> Card {
                Body("Paste the code a site shows for Ting ID, or scan it from the wallet.")
                Field(code, { code = it }, label = "Code", placeholder = "ting:sign?url=…", keyboardType = KeyboardType.Uri)
                error?.let { Banner(sentence(it), BannerTone.Danger) }
                Button("Open", onClick = ::open, icon = Icons.Outlined.ArrowUpward, enabled = code.isNotBlank())
            }
            is Step.Loading -> Card {
                Loading()
                Caption("Asking the site for the request…", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            is Step.Failed -> Card {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    IconCircle(Icons.Outlined.ErrorOutline, p.danger, p.dangerSoft, 48.dp)
                    Sub("This request can’t be signed")
                    Caption(sentence(s.message), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Spacing.xs))
                    Button("Close", onClick = { nav.back() }, kind = ButtonKind.Secondary)
                }
            }
            is Step.Review, is Step.Sending -> {
                val request = if (s is Step.Review) s.request else (s as Step.Sending).request
                key(request.nonce.hex()) {
                    Review(
                        request = request,
                        user = w.user,
                        connected = w.info != null,
                        busy = s is Step.Sending,
                        error = error,
                        problem = signerProblem(request, w.user, chainHex),
                        signers = possibleSigners(request, users, chainHex),
                        onSigner = { services.store.setActiveUser(it) },
                        onSign = { sign(request) },
                        onDecline = { decline(request) },
                    )
                }
            }
            is Step.Done -> Card {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    IconCircle(
                        if (s.signed) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                        if (s.signed) p.success else p.muted,
                        if (s.signed) p.successSoft else p.cardAlt,
                        44.dp,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Sub(if (s.signed) "Signed for ${siteName(s.request.origin)}" else "Declined")
                        Caption(sentence(s.message))
                    }
                }
                val back = s.request.returnTo
                if (back != null) Button("Back to ${siteName(s.request.origin)}", onClick = { openSite(activity, back) }, icon = Icons.Outlined.ArrowUpward)
                Button(
                    "Done",
                    onClick = { nav.back() },
                    modifier = if (back != null) Modifier.align(Alignment.CenterHorizontally) else Modifier,
                    kind = if (back != null) ButtonKind.Ghost else ButtonKind.Primary,
                )
            }
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

/** The state of the file a statement covers. */
private sealed class FileState {
    data object Idle : FileState()
    data object Opening : FileState()
    data object Opened : FileState()
    class Failed(val message: String) : FileState()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Review(
    request: SignRequest,
    user: User?,
    connected: Boolean,
    busy: Boolean,
    error: String?,
    problem: String?,
    signers: List<User>,
    onSigner: (String) -> Unit,
    onSign: () -> Unit,
    onDecline: () -> Unit,
) {
    val services = LocalServices.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val p = t
    val doc = request.request
    // Read again now and then, so that the times and the expiry stay true while the request shows.
    var now by remember { mutableLongStateOf(nowSeconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = nowSeconds()
        }
    }
    val d = describeSignRequest(doc.request, user?.name ?: "you", now)
    val site = siteName(request.origin)
    val secure = request.origin.startsWith("https://")
    val expired = request.expiresAt <= now
    // A statement's file is fetched, checked against its hash, and opened before it can be signed.
    val attachment = (doc.request as? Offchain.Request.Statement)?.attachment
    var file by remember { mutableStateOf<FileState>(FileState.Idle) }
    // A request that just appeared, perhaps under the finger, cannot be signed by the same tap.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1000)
        armed = true
    }
    fun open() {
        if (attachment == null) return
        file = FileState.Opening
        scope.launch {
            file = try {
                val bytes = services.signing.fetchAttachment(request)
                openFile(activity, bytes, attachment.name, attachment.mediaType)
                FileState.Opened
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileState.Failed(e.message ?: "the file could not be opened")
            }
        }
    }
    // The phone cannot tell whether the file was read: the share sheet answers the same when it is
    // only dismissed. So the person says so, once it is checked and handed over.
    var read by remember { mutableStateOf(false) }
    val unopened = attachment != null && (file != FileState.Opened || !read)

    // Who asks: the site, as the app knows it.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val shape = RoundedCornerShape(Radius.md)
        Box(Modifier.size(40.dp).clip(shape).background(p.card).border(Dp.Hairline, p.border, shape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Language, null, tint = p.muted, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(site, style = manropeStyle(15f, FontWeight.Medium), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (secure) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.Lock, null, tint = p.success, modifier = Modifier.size(12.dp))
                    Caption("Secure connection")
                }
            }
        }
        if (!secure) Pill("In development", Tone.Warning, small = true)
    }

    // What signing does, in the app's words, and who signs.
    Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(d.headline, style = Type.heading, color = p.text)
        d.detail?.let { Caption(it) }
    }
    val scopes = (doc.request as? Offchain.Request.Grant)?.scopes.orEmpty()
    if (scopes.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in scopes) {
                val shape = RoundedCornerShape(6.dp)
                Text(
                    s,
                    style = Type.mono.copy(fontSize = 12.5.sp),
                    color = p.text,
                    modifier = Modifier.clip(shape).background(p.cardAlt).border(Dp.Hairline, p.border, shape).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
    if (signers.size > 1) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Caption("Sign as")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                for (u in signers) {
                    val selected = u.id == user?.id
                    val shape = RoundedCornerShape(999.dp)
                    Row(
                        Modifier.clip(shape).background(p.card).border(Dp.Hairline, if (selected) p.ink else p.border, shape)
                            .clickable(enabled = !busy, role = Role.RadioButton) { onSigner(u.id) }
                            .semantics { contentDescription = if (selected) "${u.name}, signing" else "Sign as ${u.name}" }
                            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Avatar(u.name, u.color, 24.dp)
                        Text(u.name, style = manropeStyle(13.5f, FontWeight.SemiBold), color = p.text)
                    }
                }
            }
        }
    }
    d.warning?.let { Banner(it, BannerTone.Warning) }

    // The file the statement covers: only the exact file it names opens.
    if (attachment != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = RoundedCornerShape(Radius.lg)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(p.card).border(Dp.Hairline, if (file == FileState.Opened) p.success else p.border, shape).padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconCircle(Icons.Outlined.Description, p.muted, p.cardAlt, 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(attachment.name, style = manropeStyle(14.5f, FontWeight.Medium), color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Caption(if (file == FileState.Opened) "Checked: it is the file the statement names" else attachment.mediaType)
                }
                Button(
                    "Open",
                    onClick = ::open,
                    kind = ButtonKind.Secondary,
                    small = true,
                    icon = if (file == FileState.Opened) Icons.Outlined.Check else Icons.AutoMirrored.Outlined.OpenInNew,
                    loading = file == FileState.Opening,
                )
            }
            (file as? FileState.Failed)?.let { Banner(sentence(it.message), BannerTone.Danger) }
            if (file == FileState.Opened) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("I have read the file", style = manropeStyle(14.5f, FontWeight.SemiBold), color = p.text, modifier = Modifier.weight(1f))
                    Switch(
                        checked = read,
                        onCheckedChange = { read = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = p.primary, uncheckedTrackColor = p.border),
                        modifier = Modifier.semantics { contentDescription = "I have read the file" },
                    )
                }
            }
        }
    }

    // The number the site shows beside its code: the same one means the same request.
    if (doc.request is Offchain.Request.SignIn) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(p.primarySoft).padding(vertical = 14.dp, horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(Offchain.matchCode(request.nonce), style = manropeStyle(40f, FontWeight.SemiBold, tabular = true).copy(letterSpacing = 1.sp, lineHeight = 46.sp), color = p.primary)
            Text(
                "The site shows the same number. If it shows another, decline.",
                style = manropeStyle(14f, FontWeight.SemiBold).copy(lineHeight = 20.sp),
                color = p.primary,
                modifier = Modifier.weight(1f),
            )
        }
    }

    // What the site wrote, shown as written and nothing more.
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(p.cardAlt).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Message from the site", style = Type.caption, color = p.faint)
        Sub(doc.title)
        for (block in Offchain.bodyBlocks(doc.body)) {
            when (block) {
                is Offchain.Block.Paragraph -> Column { for (line in block.lines) Spans(line) }
                is Offchain.Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (item in block.items) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Caption(item.marker, Modifier.widthIn(min = 18.dp), textAlign = TextAlign.End)
                            Box(Modifier.weight(1f)) { Spans(item.spans) }
                        }
                    }
                }
            }
        }
        if (doc.fields.isNotEmpty()) {
            Column(Modifier.padding(top = 4.dp)) {
                Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(p.border))
                doc.fields.forEachIndexed { i, f ->
                    if (i > 0) Spacer(Modifier.fillMaxWidth().height(0.5.dp).background(p.border))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Caption(f.label, Modifier.weight(0.45f, fill = false).padding(end = Spacing.md))
                        Text(f.value, style = manropeStyle(14f, FontWeight.SemiBold), color = p.text, textAlign = TextAlign.End, modifier = Modifier.weight(0.55f, fill = false))
                    }
                }
            }
        }
    }

    problem?.let { Banner("This device cannot sign it: $it.", BannerTone.Warning) }
    error?.let { Banner(sentence(it), BannerTone.Danger) }
    Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        Icon(Icons.Outlined.Schedule, null, tint = if (expired) p.danger else p.faint, modifier = Modifier.size(14.dp))
        Text(
            when {
                expired -> "Expired. Start again on the site."
                unopened -> if (file == FileState.Opened) "Read the file, then say so above" else "Open the file to sign"
                else -> "Expires ${relativeTime(request.expiresAt, now)}"
            },
            style = Type.caption,
            color = if (expired) p.danger else p.muted,
        )
    }
    Button(
        d.action,
        onClick = onSign,
        icon = Icons.Outlined.Fingerprint,
        large = true,
        loading = busy,
        enabled = problem == null && !expired && !unopened && armed && connected,
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button("Decline", onClick = onDecline, kind = ButtonKind.Ghost, enabled = !busy)
    }
}

/** A line of the site's text, with its bold and italic marks. */
@Composable
private fun Spans(spans: List<Offchain.Span>) {
    val text = buildAnnotatedString {
        for (s in spans) {
            withStyle(SpanStyle(fontWeight = if (s.bold) FontWeight.Medium else null, fontStyle = if (s.italic) FontStyle.Italic else null)) { append(s.text) }
        }
    }
    Text(text, style = Type.body, color = t.text)
}

/** Opens the site's own page again; it is on the site that asked (core: Offchain checks it). */
private fun openSite(activity: FragmentActivity, url: String) {
    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

/** Back to the screen before, or to the wallet when a link opened this one. */
private fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack()
    else navigate(WalletTab) { popUpTo(graph.id) { inclusive = true } }
}
