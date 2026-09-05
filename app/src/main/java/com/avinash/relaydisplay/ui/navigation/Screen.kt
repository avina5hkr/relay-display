package com.avinash.relaydisplay.ui.navigation

import com.avinash.relaydisplay.domain.model.DeviceRole

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue


/**
 * Which kind of content the send screen should lead with.
 *
 * The dashboard tiles used to be six doors into the same undifferentiated room; this is what
 * lets each one actually mean what its label says.
 */
enum class SendFocus(val slug: String) {
    TEXT("text"),
    QR("qr"),
    LINK("link"),
    ;

    companion object {
        fun fromSlug(slug: String): SendFocus? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * The screens the app can show.
 *
 * Hand-rolled rather than androidx.navigation, for one concrete reason: navigation-compose
 * requires minSdk 24 and the companion phone is API 23 hardware. The graph is a dozen
 * destinations with no deep-link routing, so a back stack of these objects is the whole need.
 */
sealed class Screen(val route: String) {
    /**
     * The role this destination belongs to, or null when it works under either.
     *
     * This is what makes a role change able to rebuild the back stack correctly: anything
     * belonging to the outgoing role is dropped, anything role-agnostic is kept.
     */
    open val role: DeviceRole? get() = null

    data object RoleChooser : Screen("role")
    data object ControllerHome : Screen("controller") {
        override val role get() = DeviceRole.CONTROLLER
    }
    data object DisplayHome : Screen("display") {
        override val role get() = DeviceRole.DISPLAY
    }
    data object Pairing : Screen("pairing")
    data object ScanPairingCode : Screen("scan") {
        override val role get() = DeviceRole.CONTROLLER
    }
    /**
     * The send screen, opened on a particular kind of content.
     *
     * A data class rather than an object because the choice the user made on the dashboard has
     * to survive into the screen -- and, since the back stack is persisted as route strings, has
     * to survive process death too. Encoding it in the route is what makes that free.
     */
    data class Send(val focus: SendFocus) : Screen("send-${focus.slug}") {
        override val role get() = DeviceRole.CONTROLLER
    }
    data object Presentation : Screen("presentation") {
        override val role get() = DeviceRole.DISPLAY
    }
    data object Settings : Screen("settings")
    data object Diagnostics : Screen("diagnostics")
    data object About : Screen("about")

    companion object {
        /**
         * Route to screen, matched directly.
         *
         * Deliberately a `when` rather than a lookup over a `listOf(RoleChooser, ...)` held in
         * this companion. That list was a class-initialization-order trap: `fromRoute` is the
         * first thing touched when `rememberSaveable` restores the back stack after process
         * death, and if the companion initialises while the nested objects have not, the list
         * captures nulls and the very next `it.route` throws NPE. Observed on device during
         * Activity recreation. A `when` has no initialisation order to get wrong.
         */
        fun fromRoute(route: String): Screen? = when (route) {
            RoleChooser.route -> RoleChooser
            ControllerHome.route -> ControllerHome
            DisplayHome.route -> DisplayHome
            Pairing.route -> Pairing
            ScanPairingCode.route -> ScanPairingCode
            in SendFocus.entries.map { "send-${it.slug}" } ->
                Send(SendFocus.entries.first { "send-${it.slug}" == route })
            Presentation.route -> Presentation
            Settings.route -> Settings
            Diagnostics.route -> Diagnostics
            About.route -> About
            else -> null
        }
    }
}

/**
 * A minimal back stack that survives configuration change and process death, because it saves
 * only route strings.
 */
class RelayNavigator(initial: Screen) {
    private var stack by mutableStateOf(listOf(initial))

    val current: Screen get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    fun navigateTo(screen: Screen) {
        // Tapping the same destination twice must not stack duplicates.
        if (stack.last() == screen) return
        stack = stack + screen
    }

    /** Replaces the whole stack. Used when the role changes and the old screens make no sense. */
    fun resetTo(screen: Screen) {
        stack = listOf(screen)
    }

    /** Returns false when there was nothing to pop, so the caller can let the system handle back. */
    fun goBack(): Boolean {
        if (!canGoBack) return false
        stack = stack.dropLast(1)
        return true
    }

    /**
     * Rebuilds the stack for a new role.
     *
     * The bug this fixes: the previous implementation only replaced the stack when the *current*
     * screen was a role home. Changing the role from Settings left the stack as
     * `[ControllerHome, Settings]`, so the old role's dashboard stayed underneath and Back landed
     * the user right back on it -- looking exactly as though the role had not changed.
     *
     * The root is always the given role's home. Screens above it survive when they work under
     * either role (Settings, Diagnostics, About) *or* belong to the role being rebased to -- so
     * the user stays where they are and sees the confirmation, while Back now leads somewhere
     * that makes sense.
     *
     * Keeping same-role screens matters beyond the role change itself: this also runs on the
     * first composition after process death, and dropping every role-specific destination there
     * would silently throw away a restored back stack.
     */
    fun rebaseForRole(role: DeviceRole?) {
        val home = when (role) {
            null -> Screen.RoleChooser
            DeviceRole.CONTROLLER -> Screen.ControllerHome
            DeviceRole.DISPLAY -> Screen.DisplayHome
        }
        // Without a role there is nothing to be on top of: the chooser is the whole app.
        if (role == null) {
            stack = listOf(home)
            return
        }
        val kept = stack.drop(1).filter { (it.role == null || it.role == role) && it != Screen.RoleChooser }
        stack = listOf(home) + kept
    }

    internal fun routes(): List<String> = stack.map { it.route }

    internal fun restore(routes: List<String>) {
        val restored = routes.mapNotNull { Screen.fromRoute(it) }
        if (restored.isNotEmpty()) stack = restored
    }
}

@Composable
fun rememberRelayNavigator(initial: Screen): RelayNavigator = rememberSaveable(
    saver = listSaver(
        save = { it.routes() },
        restore = { routes ->
            RelayNavigator(Screen.fromRoute(routes.firstOrNull().orEmpty()) ?: Screen.RoleChooser)
                .apply { restore(routes) }
        },
    ),
) { RelayNavigator(initial) }
