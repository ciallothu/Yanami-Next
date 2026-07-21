package com.sekusarisu.yanami.ui.screen.client

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClientInstallCommandGeneratorTest {

    @Test
    fun posixArgumentsAreQuotedAndInstallerIsPinned() {
        val token = "token;$(id)'suffix"
        val linux =
                generateInstallCommand(
                        serverBaseUrl = "https://monitor.example",
                        token = token,
                        platform = InstallPlatform.LINUX,
                        options = ClientInstallOptions()
                )

        assertTrue(linux.contains(KOMARI_AGENT_INSTALL_COMMIT))
        assertTrue(linux.contains(KOMARI_AGENT_POSIX_INSTALL_SHA256))
        assertTrue(linux.contains("sudo bash \"\$install_file\""))
        assertTrue(linux.endsWith(quotePosixInstallArgument(token)))
        assertFalse(linux.contains("refs/heads/main"))
        assertFalse(linux.contains("| sudo bash"))
    }

    @Test
    fun macOsDownloadsVerifiesThenExecutesWithoutProcessSubstitution() {
        val command =
                generateInstallCommand(
                        serverBaseUrl = "https://monitor.example",
                        token = "safe-token",
                        platform = InstallPlatform.MACOS,
                        options = ClientInstallOptions()
                )

        assertTrue(command.contains("shasum -a 256"))
        assertTrue(command.contains("bash \"\$install_file\""))
        assertFalse(command.contains("<("))
        assertFalse(command.contains("refs/heads/main"))
    }

    @Test
    fun powershellUsesEncodedScriptPinnedByCommitAndChecksum() {
        val token = "token'; Write-Output pwn; # $(Get-Item Env:PATH)"
        val command =
                generateInstallCommand(
                        serverBaseUrl = "https://monitor.example",
                        token = token,
                        platform = InstallPlatform.WINDOWS,
                        options =
                                ClientInstallOptions(
                                        useInstallDir = true,
                                        dir = "C:\\Program Files\\Komari's Agent"
                                )
                )
        val script = decodePowerShell(command)

        assertTrue(command.startsWith("powershell.exe -NoProfile -ExecutionPolicy Bypass"))
        assertFalse(command.contains(token))
        assertTrue(script.contains(KOMARI_AGENT_INSTALL_COMMIT))
        assertTrue(script.contains(KOMARI_AGENT_POWERSHELL_INSTALL_SHA256))
        assertTrue(script.contains(quotePowerShellInstallArgument(token)))
        assertTrue(script.contains("'C:\\Program Files\\Komari''s Agent'"))
        assertFalse(script.contains("refs/heads/main"))
    }

    @Test
    fun quotingPreservesShellMetacharactersAsOneLiteralArgument() {
        assertEquals(
                "'token;$(id)'\"'\"'suffix'",
                quotePosixInstallArgument("token;$(id)'suffix")
        )
        assertEquals(
                "'token''; $(Get-Item Env:PATH)'",
                quotePowerShellInstallArgument("token'; $(Get-Item Env:PATH)")
        )
    }

    @Test
    fun rejectsCrLfAndNullInRequiredAndOptionalValues() {
        for (control in charArrayOf('\r', '\n', '\u0000')) {
            expectIllegalArgument {
                generateInstallCommand(
                        serverBaseUrl = "https://monitor.example",
                        token = "safe" + control + "unsafe",
                        platform = InstallPlatform.LINUX,
                        options = ClientInstallOptions()
                )
            }
            expectIllegalArgument {
                generateInstallCommand(
                        serverBaseUrl = "https://monitor.example" + control + ".invalid",
                        token = "safe-token",
                        platform = InstallPlatform.WINDOWS,
                        options = ClientInstallOptions()
                )
            }
            expectIllegalArgument {
                generateInstallCommand(
                        serverBaseUrl = "https://monitor.example",
                        token = "safe-token",
                        platform = InstallPlatform.MACOS,
                        options =
                                ClientInstallOptions(
                                        useServiceName = true,
                                        serviceName = "komari" + control + "evil"
                                )
                )
            }
        }
    }

    private fun decodePowerShell(command: String): String {
        val encoded = command.substringAfter("-EncodedCommand ")
        return String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
