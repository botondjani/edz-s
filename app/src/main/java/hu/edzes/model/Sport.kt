package hu.edzes.model

fun sportName(sport: String) = when (sport) {
    "run" -> "Futás"; "bike" -> "Kerékpár"; "swim" -> "Úszás"; "strength" -> "Súlyzós edzés"
    "hiit" -> "HIIT"; "walk" -> "Séta"; else -> "Edzés"
}

fun sportEmoji(sport: String) = when (sport) {
    "run" -> "🏃"; "bike" -> "🚴"; "swim" -> "🏊"; "strength" -> "🏋️"; "hiit" -> "⏱️"; "walk" -> "🚶"; else -> "💪"
}

/** A kardió sportokat az óra rögzíti; a többit (konditerem) az app. Tervpárosításnál a termi sportok felcserélhetők. */
fun isCardio(sport: String) = sport in setOf("run", "bike", "swim", "walk")
fun sportGroup(sport: String) = if (isCardio(sport)) sport else "gym"

fun stravaType(sport: String) = when (sport) {
    "run" -> "Run"; "bike" -> "Ride"; "swim" -> "Swim"; "strength" -> "WeightTraining"
    "hiit" -> "HIIT"; "walk" -> "Walk"; else -> "Workout"
}

fun sportOfStrava(type: String) = when {
    type.endsWith("Run") -> "run"
    type.endsWith("Ride") -> "bike"
    type == "Swim" -> "swim"
    type == "WeightTraining" -> "strength"
    type == "HIIT" || type == "Crossfit" -> "hiit"
    type == "Walk" || type == "Hike" -> "walk"
    else -> "other"
}
