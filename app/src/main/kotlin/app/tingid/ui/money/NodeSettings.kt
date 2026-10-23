// Settings → Nodes: the nodes the app uses as one, each one's state, and
// adding or removing them.
package app.tingid.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tingid.chain.NodeState
import app.tingid.chain.nodeAddress
import app.tingid.chain.remoteHttp
import app.tingid.state.WorkspaceState
import app.tingid.text.NODES_INFO
import app.tingid.text.NodeTone
import app.tingid.text.describeNode
import app.tingid.ui.Banner
import app.tingid.ui.BannerTone
import app.tingid.ui.Button
import app.tingid.ui.ButtonKind
import app.tingid.ui.Caption
import app.tingid.ui.Card
import app.tingid.ui.Field
import app.tingid.ui.IconButton
import app.tingid.ui.IdChip
import app.tingid.ui.LocalServices
import app.tingid.ui.SectionHeader
import app.tingid.ui.Spacing
import app.tingid.ui.Type
import app.tingid.ui.t

/** The address the field suggests, and the example its problem names: a node on this device, as the first app's default. */
const val EXAMPLE_NODE_URL = "http://127.0.0.1:18441"

/**
 * The Nodes section of Settings, for the workspace `w`. `missing`: the names
 * of the standard contracts the chain lacks, if any.
 */
@Composable
fun NodeSettings(w: WorkspaceState, missing: List<String>) {
    val p = t
    val services = LocalServices.current
    val pool by services.chain.pool.collectAsState()
    val nodes by pool.healthFlow.collectAsState()
    val bestHeight = nodes.firstOrNull { it.best }?.status?.height
    // Nodes that answer on the chain: with fewer than two, no node's answers are checked against another's.
    val answering = nodes.count { it.state == NodeState.Ok }
    val nodeUrls = services.store.data.collectAsState().value.settings.nodeUrls
    var input by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    fun toneColor(tone: NodeTone) = when (tone) {
        NodeTone.Success -> p.success
        NodeTone.Muted -> p.faint
        NodeTone.Warning -> p.warning
        NodeTone.Danger -> p.danger
    }

    fun add() {
        val url = nodeAddress(input)
        problem = when {
            remoteHttp(input) -> "Plain http is only for a node on this device or your home network. Use the node’s https address."
            url == null -> "Enter an address such as $EXAMPLE_NODE_URL."
            url in nodeUrls -> "That node is in the list already."
            else -> {
                services.store.setSettings { it.copy(nodeUrls = it.nodeUrls + url) }
                input = ""
                null
            }
        }
    }

    SectionHeader("Nodes", action = "Refresh", onAction = services.workspace::refresh, info = NODES_INFO)
    Card(padded = false) {
        Column {
            nodes.forEachIndexed { i, n ->
                val d = describeNode(n, bestHeight)
                if (i > 0) Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(toneColor(d.tone)))
                    Column(Modifier.weight(1f).padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(n.url, style = Type.mono, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ToneCaption(d.text, if (d.tone == NodeTone.Warning || d.tone == NodeTone.Danger) toneColor(d.tone) else p.muted)
                    }
                    if (nodes.size > 1) {
                        IconButton(
                            Icons.Outlined.Close,
                            "Remove ${n.url}",
                            onClick = { services.store.setSettings { s -> s.copy(nodeUrls = s.nodeUrls.filter { it != n.url }) } },
                            kind = ButtonKind.Ghost,
                            size = 36.dp,
                        )
                    }
                }
            }
            Hairline()
            Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Field(
                    input,
                    {
                        input = it
                        problem = null
                    },
                    label = "Add a node",
                    placeholder = EXAMPLE_NODE_URL,
                    keyboardType = KeyboardType.Uri,
                    error = problem,
                )
                Row {
                    Button("Add", onClick = ::add, small = true, icon = Icons.Outlined.Add, enabled = input.isNotBlank())
                }
                if (answering < 2) {
                    Banner(
                        if (answering == 1) {
                            "Only one node answers, so nothing checks it: a wrong or dishonest node could hide what happened on chain, such as a withdrawal to cancel. Add a second node, run by someone else."
                        } else {
                            "No node answers. Add one, or check the ones listed."
                        },
                        BannerTone.Warning,
                        Icons.AutoMirrored.Outlined.CompareArrows,
                    )
                }
                val info = w.info
                if (info != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Caption("Chain")
                            IdChip(info.chainHex)
                        }
                        Caption("Height ${info.status.height}${if (info.status.mempoolCount != 0L) " · ${info.status.mempoolCount} pending" else ""}")
                    }
                }
                if (missing.isNotEmpty()) Banner("This chain lacks ${missing.joinToString(", ")}; identities and accounts need them.", BannerTone.Warning)
            }
        }
    }
}
