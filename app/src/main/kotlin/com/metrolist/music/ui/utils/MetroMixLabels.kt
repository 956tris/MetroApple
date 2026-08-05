/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R
import com.metrolist.music.constants.MetroMixEffectCurve
import com.metrolist.music.constants.MetroMixEqCurve
import com.metrolist.music.constants.MetroMixPreset
import com.metrolist.music.constants.MetroMixVolumeCurve

@Composable
fun metroMixPresetLabel(preset: MetroMixPreset): String =
    when (preset) {
        // No string resource yet (see MetroMixStudioDialog's PresetStrip note) -
        // literal string avoids needing a new R.string.* entry across all locales.
        MetroMixPreset.AUTOMIX -> "Automix"
        MetroMixPreset.AUTO -> stringResource(R.string.metromix_preset_auto)
        MetroMixPreset.SMART_DJ -> stringResource(R.string.metromix_preset_smart_dj)
        MetroMixPreset.BEAT_BLEND -> stringResource(R.string.metromix_preset_beat_blend)
        MetroMixPreset.ENERGY_MATCH -> stringResource(R.string.metromix_preset_energy_match)
        MetroMixPreset.CLUB_BLEND -> stringResource(R.string.metromix_preset_club_blend)
        MetroMixPreset.VOCAL_BLEND -> stringResource(R.string.metromix_preset_vocal_blend)
        MetroMixPreset.BASS_SWAP -> stringResource(R.string.metromix_preset_bass_swap)
        MetroMixPreset.RADIO_EDIT -> stringResource(R.string.metromix_preset_radio_edit)
        MetroMixPreset.QUICK_CUT -> stringResource(R.string.metromix_preset_quick_cut)
        MetroMixPreset.LOOP_OUT -> stringResource(R.string.metromix_preset_loop_out)
        MetroMixPreset.FADE -> stringResource(R.string.metromix_preset_fade)
        MetroMixPreset.RISE -> stringResource(R.string.metromix_preset_rise)
        MetroMixPreset.BLEND -> stringResource(R.string.metromix_preset_blend)
        MetroMixPreset.DROP -> stringResource(R.string.metromix_preset_drop)
        MetroMixPreset.ECHO_OUT -> stringResource(R.string.metromix_preset_echo_out)
        MetroMixPreset.SMOOTH -> stringResource(R.string.metromix_preset_smooth)
        MetroMixPreset.LONG_BLEND -> stringResource(R.string.metromix_preset_long_blend)
    }

@Composable
fun metroMixPresetDescription(preset: MetroMixPreset): String =
    when (preset) {
        MetroMixPreset.AUTOMIX -> "Tempo, key, and beat-phase matched blend - the closest thing to a real DJ transition"
        MetroMixPreset.AUTO -> stringResource(R.string.metromix_preset_auto_desc)
        MetroMixPreset.SMART_DJ -> stringResource(R.string.metromix_preset_smart_dj_desc)
        MetroMixPreset.BEAT_BLEND -> stringResource(R.string.metromix_preset_beat_blend_desc)
        MetroMixPreset.ENERGY_MATCH -> stringResource(R.string.metromix_preset_energy_match_desc)
        MetroMixPreset.CLUB_BLEND -> stringResource(R.string.metromix_preset_club_blend_desc)
        MetroMixPreset.VOCAL_BLEND -> stringResource(R.string.metromix_preset_vocal_blend_desc)
        MetroMixPreset.BASS_SWAP -> stringResource(R.string.metromix_preset_bass_swap_desc)
        MetroMixPreset.RADIO_EDIT -> stringResource(R.string.metromix_preset_radio_edit_desc)
        MetroMixPreset.QUICK_CUT -> stringResource(R.string.metromix_preset_quick_cut_desc)
        MetroMixPreset.LOOP_OUT -> stringResource(R.string.metromix_preset_loop_out_desc)
        MetroMixPreset.FADE -> stringResource(R.string.metromix_preset_fade_desc)
        MetroMixPreset.RISE -> stringResource(R.string.metromix_preset_rise_desc)
        MetroMixPreset.BLEND -> stringResource(R.string.metromix_preset_blend_desc)
        MetroMixPreset.DROP -> stringResource(R.string.metromix_preset_drop_desc)
        MetroMixPreset.ECHO_OUT -> stringResource(R.string.metromix_preset_echo_out_desc)
        MetroMixPreset.SMOOTH -> stringResource(R.string.metromix_preset_smooth_desc)
        MetroMixPreset.LONG_BLEND -> stringResource(R.string.metromix_preset_long_blend_desc)
    }

@Composable
fun metroMixVolumeCurveLabel(curve: MetroMixVolumeCurve): String =
    when (curve) {
        MetroMixVolumeCurve.AUTO -> stringResource(R.string.metromix_curve_auto)
        MetroMixVolumeCurve.BALANCED -> stringResource(R.string.metromix_volume_overlap)
        MetroMixVolumeCurve.PUNCHY -> stringResource(R.string.metromix_volume_fade_in_out)
        MetroMixVolumeCurve.MELT -> stringResource(R.string.metromix_volume_smooth_crossfade)
        MetroMixVolumeCurve.WAVE -> stringResource(R.string.metromix_volume_cut_in_fade_out)
    }

@Composable
fun metroMixEqCurveLabel(curve: MetroMixEqCurve): String =
    when (curve) {
        MetroMixEqCurve.AUTO -> stringResource(R.string.metromix_eq_auto)
        MetroMixEqCurve.CLEAN -> stringResource(R.string.metromix_eq_flat)
        MetroMixEqCurve.BASS_SWAP -> stringResource(R.string.metromix_eq_center_bass_swap)
        MetroMixEqCurve.VOCAL_SPACE -> stringResource(R.string.metromix_eq_vocal_space)
        MetroMixEqCurve.FULL -> stringResource(R.string.metromix_eq_three_band_fade)
    }

@Composable
fun metroMixEffectCurveLabel(curve: MetroMixEffectCurve): String =
    when (curve) {
        MetroMixEffectCurve.AUTO -> stringResource(R.string.metromix_effect_auto)
        MetroMixEffectCurve.NONE -> stringResource(R.string.metromix_effect_none)
        MetroMixEffectCurve.FILTER -> stringResource(R.string.metromix_effect_low_pass)
        MetroMixEffectCurve.ECHO -> stringResource(R.string.metromix_effect_echo_out)
        MetroMixEffectCurve.WAVE -> stringResource(R.string.metromix_effect_sweep)
    }
