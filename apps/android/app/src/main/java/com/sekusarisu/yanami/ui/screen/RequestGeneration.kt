package com.sekusarisu.yanami.ui.screen

/** Pure request-generation check shared by refresh flows that reject stale completions. */
internal fun isCurrentRequestGeneration(completed: Long, active: Long): Boolean =
        completed == active
