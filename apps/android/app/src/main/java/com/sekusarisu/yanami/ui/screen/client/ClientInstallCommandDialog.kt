package com.sekusarisu.yanami.ui.screen.client

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sekusarisu.yanami.R
import com.sekusarisu.yanami.domain.model.ManagedClient
import java.net.URI
import java.util.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstallCommandDialog(
        client: ManagedClient,
        serverBaseUrl: String,
        onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val commandCopiedMessage =
            stringResource(R.string.client_management_install_command_copied)
    var selectedPlatform by remember(client.uuid) { mutableStateOf(InstallPlatform.LINUX) }
    var installOptionsExpanded by remember(client.uuid) { mutableStateOf(false) }
    var installOptions by remember(client.uuid) { mutableStateOf(ClientInstallOptions()) }
    val generatedCommandResult =
            remember(client.uuid, serverBaseUrl, selectedPlatform, installOptions) {
                runCatching {
                    generateInstallCommand(
                            serverBaseUrl = serverBaseUrl,
                            token = client.token,
                            platform = selectedPlatform,
                            options = installOptions
                    )
                }
            }
    val generatedCommand =
            generatedCommandResult.getOrElse {
                it.message ?: "Invalid install command parameters"
            }

    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.client_management_install_command_title)) },
            text = {
                Column(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .heightIn(max = 520.dp)
                                        .verticalScroll(rememberScrollState())
                ) {
                    Text(client.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(client.uuid, style = MaterialTheme.typography.bodySmall)

                    Spacer(modifier = Modifier.height(16.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                                selected = selectedPlatform == InstallPlatform.LINUX,
                                onClick = { selectedPlatform = InstallPlatform.LINUX },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) {
                            Text(stringResource(R.string.client_management_platform_linux))
                        }
                        SegmentedButton(
                                selected = selectedPlatform == InstallPlatform.WINDOWS,
                                onClick = { selectedPlatform = InstallPlatform.WINDOWS },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) {
                            Text(stringResource(R.string.client_management_platform_windows))
                        }
                        SegmentedButton(
                                selected = selectedPlatform == InstallPlatform.MACOS,
                                onClick = { selectedPlatform = InstallPlatform.MACOS },
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                        ) {
                            Text(stringResource(R.string.client_management_platform_macos))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                                text = stringResource(R.string.client_management_install_options),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = { installOptionsExpanded = !installOptionsExpanded }) {
                            Text(
                                    stringResource(
                                            if (installOptionsExpanded) R.string.node_collapse
                                            else R.string.node_expand
                                    )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                    imageVector =
                                            if (installOptionsExpanded) Icons.Default.ArrowUpward
                                            else Icons.Default.ArrowDownward,
                                    contentDescription = null
                            )
                        }
                    }

                    if (installOptionsExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        InstallOptionToggleRow(
                                checked = installOptions.disableWebSsh,
                                label = stringResource(R.string.client_management_disable_web_ssh),
                                onCheckedChange = {
                                    installOptions = installOptions.copy(disableWebSsh = it)
                                }
                        )
                        InstallOptionToggleRow(
                                checked = installOptions.disableAutoUpdate,
                                label =
                                        stringResource(
                                                R.string.client_management_disable_auto_update
                                        ),
                                onCheckedChange = {
                                    installOptions = installOptions.copy(disableAutoUpdate = it)
                                }
                        )
                        InstallOptionToggleRow(
                                checked = installOptions.ignoreUnsafeCert,
                                label =
                                        stringResource(
                                                R.string.client_management_ignore_unsafe_cert
                                        ),
                                onCheckedChange = {
                                    installOptions = installOptions.copy(ignoreUnsafeCert = it)
                                }
                        )
                        InstallOptionToggleRow(
                                checked = installOptions.memoryIncludeCache,
                                label =
                                        stringResource(
                                                R.string.client_management_memory_include_cache
                                        ),
                                onCheckedChange = {
                                    installOptions = installOptions.copy(memoryIncludeCache = it)
                                }
                        )
                        InstallOptionField(
                                checked = installOptions.useGhproxy,
                                label = stringResource(R.string.client_management_ghproxy),
                                value = installOptions.ghproxy,
                                placeholder =
                                        stringResource(R.string.client_management_ghproxy_placeholder),
                                onCheckedChange = {
                                    installOptions = installOptions.copy(useGhproxy = it)
                                },
                                onValueChange = { installOptions = installOptions.copy(ghproxy = it) }
                        )
                        InstallOptionField(
                                checked = installOptions.useInstallDir,
                                label = stringResource(R.string.client_management_install_dir),
                                value = installOptions.dir,
                                placeholder =
                                        stringResource(
                                                R.string.client_management_install_dir_placeholder
                                        ),
                                onCheckedChange = {
                                    installOptions = installOptions.copy(useInstallDir = it)
                                },
                                onValueChange = { installOptions = installOptions.copy(dir = it) }
                        )
                        InstallOptionField(
                                checked = installOptions.useServiceName,
                                label = stringResource(R.string.client_management_service_name),
                                value = installOptions.serviceName,
                                placeholder =
                                        stringResource(
                                                R.string.client_management_service_name_placeholder
                                        ),
                                onCheckedChange = {
                                    installOptions = installOptions.copy(useServiceName = it)
                                },
                                onValueChange = {
                                    installOptions = installOptions.copy(serviceName = it)
                                }
                        )
                        InstallOptionField(
                                checked = installOptions.useIncludeNics,
                                label = stringResource(R.string.client_management_include_nics),
                                value = installOptions.includeNics,
                                placeholder =
                                        stringResource(
                                                R.string.client_management_include_nics_placeholder
                                        ),
                                onCheckedChange = {
                                    installOptions = installOptions.copy(useIncludeNics = it)
                                },
                                onValueChange = {
                                    installOptions = installOptions.copy(includeNics = it)
                                }
                        )
                        InstallOptionField(
                                checked = installOptions.useExcludeNics,
                                label = stringResource(R.string.client_management_exclude_nics),
                                value = installOptions.excludeNics,
                                placeholder =
                                        stringResource(
                                                R.string.client_management_exclude_nics_placeholder
                                        ),
                                onCheckedChange = {
                                    installOptions = installOptions.copy(useExcludeNics = it)
                                },
                                onValueChange = {
                                    installOptions = installOptions.copy(excludeNics = it)
                                }
                        )
                        InstallOptionField(
                                checked = installOptions.useIncludeMountpoint,
                                label =
                                        stringResource(
                                                R.string.client_management_include_mountpoint
                                        ),
                                value = installOptions.includeMountpoint,
                                placeholder =
                                        stringResource(
                                                R.string
                                                        .client_management_include_mountpoint_placeholder
                                        ),
                                onCheckedChange = {
                                    installOptions =
                                            installOptions.copy(useIncludeMountpoint = it)
                                },
                                onValueChange = {
                                    installOptions =
                                            installOptions.copy(includeMountpoint = it)
                                }
                        )
                        InstallOptionField(
                                checked = installOptions.useMonthRotate,
                                label = stringResource(R.string.client_management_month_rotate),
                                value = installOptions.monthRotate,
                                placeholder =
                                        stringResource(
                                                R.string.client_management_month_rotate_placeholder
                                        ),
                                onCheckedChange = {
                                    installOptions = installOptions.copy(useMonthRotate = it)
                                },
                                onValueChange = {
                                    installOptions = installOptions.copy(monthRotate = it)
                                }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                            text = stringResource(R.string.client_management_generated_command),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 2.dp
                    ) {
                        SelectionContainer {
                            Text(
                                    text = generatedCommand,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                        enabled = generatedCommandResult.isSuccess,
                        onClick = {
                            clipboard.setText(AnnotatedString(generatedCommand))
                            Toast.makeText(
                                            context,
                                            commandCopiedMessage,
                                            Toast.LENGTH_SHORT
                                    )
                                    .show()
                        }
                ) { Text(stringResource(R.string.action_copy)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
    )
}

@Composable
private fun InstallOptionToggleRow(
        checked: Boolean,
        label: String,
        onCheckedChange: (Boolean) -> Unit
) {
    Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InstallOptionField(
        checked: Boolean,
        label: String,
        value: String,
        placeholder: String,
        onCheckedChange: (Boolean) -> Unit,
        onValueChange: (String) -> Unit
) {
    InstallOptionToggleRow(checked = checked, label = label, onCheckedChange = onCheckedChange)
    if (checked) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
}

internal enum class InstallPlatform {
    LINUX,
    WINDOWS,
    MACOS
}

internal data class ClientInstallOptions(
        val disableWebSsh: Boolean = false,
        val disableAutoUpdate: Boolean = false,
        val ignoreUnsafeCert: Boolean = false,
        val memoryIncludeCache: Boolean = false,
        val useGhproxy: Boolean = false,
        val ghproxy: String = "",
        val useInstallDir: Boolean = false,
        val dir: String = "",
        val useServiceName: Boolean = false,
        val serviceName: String = "",
        val useIncludeNics: Boolean = false,
        val includeNics: String = "",
        val useExcludeNics: Boolean = false,
        val excludeNics: String = "",
        val useIncludeMountpoint: Boolean = false,
        val includeMountpoint: String = "",
        val useMonthRotate: Boolean = false,
        val monthRotate: String = ""
)

internal fun generateInstallCommand(
        serverBaseUrl: String,
        token: String,
        platform: InstallPlatform,
        options: ClientInstallOptions
): String {
    requireSafeInstallValue("server URL", serverBaseUrl)
    requireSafeInstallValue("client token", token)
    val host = serverBaseUrl.toOrigin()
    require(host.isNotBlank()) { "Server URL must not be blank" }
    require(token.isNotBlank()) { "Client token must not be blank" }
    val args = mutableListOf("-e", host, "-t", token)
    if (options.disableWebSsh) args += "--disable-web-ssh"
    if (options.disableAutoUpdate) args += "--disable-auto-update"
    if (options.ignoreUnsafeCert) args += "--ignore-unsafe-cert"
    if (options.memoryIncludeCache) args += "--memory-include-cache"
    if (options.useGhproxy) {
        options.ghproxy.safeTrimmedInstallValue("GitHub proxy")?.let { ghproxy ->
            args += "--install-ghproxy"
            args += ghproxy.normalizeGhproxy()
        }
    }
    if (options.useInstallDir) {
        options.dir.safeTrimmedInstallValue("install directory")?.let { dir ->
            args += "--install-dir"
            args += dir
        }
    }
    if (options.useServiceName) {
        options.serviceName.safeTrimmedInstallValue("service name")?.let { serviceName ->
            args += "--install-service-name"
            args += serviceName
        }
    }
    if (options.useIncludeNics) {
        options.includeNics.safeTrimmedInstallValue("included network interfaces")?.let {
                includeNics ->
            args += "--include-nics"
            args += includeNics
        }
    }
    if (options.useExcludeNics) {
        options.excludeNics.safeTrimmedInstallValue("excluded network interfaces")?.let {
                excludeNics ->
            args += "--exclude-nics"
            args += excludeNics
        }
    }
    if (options.useIncludeMountpoint) {
        options.includeMountpoint.safeTrimmedInstallValue("included mount point")?.let {
                includeMountpoint ->
            args += "--include-mountpoint"
            args += includeMountpoint
        }
    }
    if (options.useMonthRotate) {
        options.monthRotate.safeTrimmedInstallValue("month rotation")?.let { monthRotate ->
            args += "--month-rotate"
            args += monthRotate
        }
    }

    return when (platform) {
        InstallPlatform.LINUX ->
                generatePosixInstallCommand(
                        args = args,
                        hashCommand = "sha256sum",
                        useSudo = true
                )
        InstallPlatform.WINDOWS -> generatePowerShellInstallCommand(args)
        InstallPlatform.MACOS ->
                generatePosixInstallCommand(
                        args = args,
                        hashCommand = "shasum -a 256",
                        useSudo = false
                )
    }
}

// Pinned from the official repository on 2026-07-21. Updating it requires reviewing both
// installer scripts and replacing their independently computed SHA-256 digests below.
internal const val KOMARI_AGENT_INSTALL_COMMIT =
        "d68d55f92adfbd94736a49de4594a19cad91e841"
internal const val KOMARI_AGENT_POSIX_INSTALL_SHA256 =
        "72c55c4c34033d2f6e69cbba11fff21173e4c3a25d39340d36a54c25e1ea97d5"
internal const val KOMARI_AGENT_POWERSHELL_INSTALL_SHA256 =
        "d5b9881a25bcdbc939ac9dfc7157a622e345ef4ec75ca2799cfa8e0bcffd584a"

private const val KOMARI_AGENT_RAW_BASE_URL =
        "https://raw.githubusercontent.com/komari-monitor/komari-agent"

private fun generatePosixInstallCommand(
        args: List<String>,
        hashCommand: String,
        useSudo: Boolean
): String = buildString {
    val scriptUrl = "$KOMARI_AGENT_RAW_BASE_URL/$KOMARI_AGENT_INSTALL_COMMIT/install.sh"
    appendLine("set -eu")
    appendLine("install_file=\"\$(mktemp)\"")
    appendLine("trap 'rm -f \"\$install_file\"' EXIT")
    appendLine(
            "curl --proto '=https' --tlsv1.2 --fail --silent --show-error --location " +
                    "${quotePosixInstallArgument(scriptUrl)} --output \"\$install_file\""
    )
    appendLine("actual_hash=\"\$($hashCommand \"\$install_file\")\"")
    appendLine("actual_hash=\"\${actual_hash%% *}\"")
    appendLine(
            "if [ \"\$actual_hash\" != " +
                    "${quotePosixInstallArgument(KOMARI_AGENT_POSIX_INSTALL_SHA256)} ]; then"
    )
    appendLine("  echo 'Komari installer checksum verification failed.' >&2")
    appendLine("  exit 1")
    appendLine("fi")
    if (useSudo) append("sudo ")
    append("bash \"\$install_file\"")
    args.forEach { arg ->
        append(' ')
        append(quotePosixInstallArgument(arg))
    }
}

private fun generatePowerShellInstallCommand(args: List<String>): String {
    val scriptUrl = "$KOMARI_AGENT_RAW_BASE_URL/$KOMARI_AGENT_INSTALL_COMMIT/install.ps1"
    val script = buildString {
        appendLine("\$ErrorActionPreference = 'Stop'")
        appendLine("[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12")
        appendLine("\$installUrl = ${quotePowerShellInstallArgument(scriptUrl)}")
        appendLine(
                "\$installFile = Join-Path ([IO.Path]::GetTempPath()) " +
                        "('komari-install-' + [Guid]::NewGuid().ToString('N') + '.ps1')"
        )
        appendLine("try {")
        appendLine(
                "  Invoke-WebRequest -Uri \$installUrl -UseBasicParsing " +
                        "-OutFile \$installFile"
        )
        appendLine(
                "  \$actualHash = (Get-FileHash -Algorithm SHA256 " +
                        "-LiteralPath \$installFile).Hash.ToLowerInvariant()"
        )
        appendLine(
                "  if (\$actualHash -ne " +
                        "${quotePowerShellInstallArgument(KOMARI_AGENT_POWERSHELL_INSTALL_SHA256)}) " +
                        "{ throw 'Komari installer checksum verification failed.' }"
        )
        append("  & \$installFile")
        args.forEach { arg ->
            append(' ')
            append(quotePowerShellInstallArgument(arg))
        }
        appendLine()
        appendLine("} finally {")
        appendLine(
                "  Remove-Item -LiteralPath \$installFile -Force " +
                        "-ErrorAction SilentlyContinue"
        )
        appendLine("}")
    }
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    return "powershell.exe -NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded"
}

internal fun quotePosixInstallArgument(value: String): String {
    requireSafeInstallValue("install argument", value)
    return "'${value.replace("'", "'\"'\"'")}'"
}

internal fun quotePowerShellInstallArgument(value: String): String {
    requireSafeInstallValue("install argument", value)
    return "'${value.replace("'", "''")}'"
}

private fun String.safeTrimmedInstallValue(label: String): String? {
    requireSafeInstallValue(label, this)
    return trim().takeIf(String::isNotEmpty)
}

private fun requireSafeInstallValue(label: String, value: String) {
    require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
        "$label contains forbidden control characters"
    }
}

private fun String.normalizeGhproxy(): String =
        if (startsWith("http://") || startsWith("https://")) this else "http://$this"

private fun String.toOrigin(): String {
    val normalized = trim().trimEnd('/')
    val parsed = runCatching { URI(normalized) }.getOrNull()
    val scheme = parsed?.scheme.orEmpty()
    val host = parsed?.host.orEmpty()
    if (scheme.isBlank() || host.isBlank()) {
        return normalized
    }
    val bracketedHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
    val port = parsed?.port ?: -1
    return if (port == -1) "$scheme://$bracketedHost" else "$scheme://$bracketedHost:$port"
}
