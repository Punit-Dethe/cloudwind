package dev.cloudwind.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutePlannerTest {
    private val mb = 1L shl 20
    private val gates = Gates() // big >= 50 MB, wifi-only for big, battery gate at 40%
    private val roomy = Limits(cap_bytes = 8L shl 30, soft_bytes = 5L shl 30, free_bytes = 8L shl 30, max_object_bytes = 5L shl 30)

    private fun cond(lan: Boolean = false, net: Boolean = true, wifi: Boolean = true, charging: Boolean = false, pct: Int = 80) =
        Conditions(lan, net, wifi, charging, pct)

    private fun photo(policy: Policy = Policy.CLOUD_OK, inCloud: Boolean = false) = FileInfo(4 * mb, policy, inCloud)
    private fun video(size: Long = 800 * mb, policy: Policy = Policy.CLOUD_OK) = FileInfo(size, policy)

    private fun decide(f: FileInfo, c: Conditions, g: Gates = gates, l: Limits? = roomy) = RoutePlanner.decide(f, c, g, l)

    // ---- the PC is reachable ----
    @Test fun lan_wins_for_everything_when_the_pc_is_reachable() {
        assertEquals(Decision.Lan, decide(photo(), cond(lan = true)))
        assertEquals(Decision.Lan, decide(video(), cond(lan = true)))
        assertEquals(Decision.Lan, decide(photo(Policy.HOME_ONLY), cond(lan = true)))
    }

    @Test fun direct_sends_ignore_wifi_and_cloud_limits_even_for_huge_files() {
        // 9 GB would be refused by the cloud, and mobile-data rules don't apply on the LAN.
        assertEquals(Decision.Lan, decide(video(9L shl 30), cond(lan = true, wifi = false), l = roomy))
    }

    @Test fun direct_big_sends_still_obey_the_battery_gate() {
        assertEquals(Decision.Wait(Wait.BATTERY), decide(video(), cond(lan = true, pct = 20)))
        assertEquals(Decision.Lan, decide(video(), cond(lan = true, pct = 20, charging = true)))
        assertEquals(Decision.Lan, decide(photo(), cond(lan = true, pct = 10)), "small files are never gated")
    }

    // ---- PC not reachable: the cloud, with gates only on big files ----
    @Test fun small_photos_go_to_the_cloud_on_any_connection() {
        assertEquals(Decision.Cloud, decide(photo(), cond(wifi = false, pct = 5)))
    }

    @Test fun big_files_wait_for_wifi_when_that_gate_is_on() {
        assertEquals(Decision.Wait(Wait.WIFI), decide(video(), cond(wifi = false)))
        // Unlimited-data users turn the gate off:
        assertEquals(Decision.Cloud, decide(video(), cond(wifi = false), g = gates.copy(wifiOnlyForBig = false)))
    }

    @Test fun big_files_wait_for_charge_or_enough_battery() {
        assertEquals(Decision.Wait(Wait.BATTERY), decide(video(), cond(pct = 39)))
        assertEquals(Decision.Cloud, decide(video(), cond(pct = 40)))
        assertEquals(Decision.Cloud, decide(video(), cond(pct = 5, charging = true)))
        assertEquals(Decision.Cloud, decide(video(), cond(pct = 5), g = gates.copy(batteryGate = false)))
    }

    @Test fun battery_threshold_is_kept_in_the_30_to_90_range() {
        assertEquals(30, Gates(batteryMinPct = 5).normalized().batteryMinPct)
        assertEquals(90, Gates(batteryMinPct = 100).normalized().batteryMinPct)
        // A hand-edited 5% still behaves as 30%.
        assertEquals(Decision.Wait(Wait.BATTERY), decide(video(), cond(pct = 29), g = Gates(batteryMinPct = 5)))
    }

    @Test fun the_big_file_threshold_is_configurable() {
        val g = gates.copy(bigBytes = 500 * mb)
        assertEquals(Decision.Cloud, decide(video(300 * mb), cond(wifi = false, pct = 10), g = g), "300 MB is not big under a 500 MB rule")
        assertEquals(Decision.Wait(Wait.WIFI), decide(video(600 * mb), cond(wifi = false), g = g))
    }

    // ---- cloud limits still apply ----
    @Test fun files_the_cloud_cannot_take_wait_for_home() {
        assertEquals(Decision.Wait(Wait.HOME_NETWORK), decide(video(6L shl 30), cond()))
    }

    @Test fun a_full_inbox_makes_the_file_wait() {
        assertEquals(Decision.Wait(Wait.INBOX_FULL), decide(video(), cond(), l = roomy.copy(free_bytes = 100 * mb)))
        assertEquals(Decision.Cloud, decide(photo(), cond(), l = roomy.copy(free_bytes = 100 * mb)), "small ones still fit")
    }

    @Test fun no_internet_means_wait() {
        assertEquals(Decision.Wait(Wait.NETWORK), decide(photo(), cond(net = false)))
    }

    @Test fun unknown_cloud_limits_do_not_block_small_uploads() {
        assertEquals(Decision.Cloud, decide(photo(), cond(), l = null))
    }

    // ---- sensitive folders and files already in the cloud ----
    @Test fun home_only_files_never_go_to_the_cloud() {
        assertEquals(Decision.Wait(Wait.HOME_NETWORK), decide(photo(Policy.HOME_ONLY), cond()))
        assertEquals(Decision.Wait(Wait.HOME_NETWORK), decide(video(policy = Policy.HOME_ONLY), cond(wifi = true, pct = 100, charging = true)))
    }

    @Test fun files_already_in_the_cloud_need_nothing_from_the_phone() {
        assertEquals(Decision.InCloud, decide(photo(inCloud = true), cond()))
    }

    // ---- switching an upload that is already under way ----
    @Test fun finish_a_nearly_done_cloud_upload_but_cancel_a_long_one() {
        assertTrue(RoutePlanner.finishCloudInstead(10 * mb))
        assertTrue(RoutePlanner.finishCloudInstead(64 * mb))
        assertFalse(RoutePlanner.finishCloudInstead(65 * mb))
        assertFalse(RoutePlanner.finishCloudInstead(-1))
    }

    // ---- folder policy ----
    @Test fun camera_is_cloud_ok_and_other_folders_default_to_home_only() {
        val none = emptySet<String>()
        assertEquals(Policy.CLOUD_OK, folderPolicy("DCIM/Camera/", none, none))
        assertEquals(Policy.CLOUD_OK, folderPolicy("DCIM", none, none))
        assertEquals(Policy.HOME_ONLY, folderPolicy("Pictures/Screenshots/", none, none))
        assertEquals(Policy.HOME_ONLY, folderPolicy("Download/", none, none))
        assertEquals(Policy.HOME_ONLY, folderPolicy("DCIMfake/", none, none), "only the real DCIM tree counts")
    }

    @Test fun explicit_choices_beat_the_default_either_way() {
        assertEquals(Policy.HOME_ONLY, folderPolicy("DCIM/Camera/", setOf("DCIM/Camera"), emptySet()))
        assertEquals(Policy.CLOUD_OK, folderPolicy("Pictures/Screenshots/", emptySet(), setOf("Pictures/Screenshots")))
        assertEquals(Policy.HOME_ONLY, folderPolicy("Documents/Scans", setOf("Documents/Scans"), setOf("Documents/Scans")), "home-only wins a tie")
    }
}
