package ai.vecto.agent

import ai.vecto.actions.ActionRouter

/**
 * Scripted flows for each supported app.
 *
 * The on-screen labels below ("Search", "Add ... to basket") are best guesses for Uber Eats UK and
 * WILL need tuning on a real device: when a step fails, the full screen tree is logged under the
 * "VectoService" tag in Logcat, so you can see the real labels and update them here.
 */
object Playbooks {

    fun orderFood(restaurant: String, items: List<String>): Playbook {
        val steps = buildList {
            add(Step("Opening Uber Eats", settleMs = 1_500) {
                it.currentPackage() == ActionRouter.UBER_EATS
            })
            add(Step("Opening search") { it.clickText("Search") })
            add(Step("Typing \"$restaurant\"", settleMs = 2_000) { it.typeIntoFirstEditable(restaurant) })
            add(Step("Opening $restaurant", settleMs = 2_500) { it.clickText(restaurant) })
            for (item in items) {
                add(Step("Finding $item", settleMs = 1_500) { it.clickText(item) })
                add(Step("Adding $item to basket", settleMs = 1_500) { it.clickTextStartingWith("Add") })
            }
        }
        // Deliberately stops here: the user reviews the basket and pays themselves.
        val done = if (items.isEmpty()) "Opened $restaurant. Pick what you'd like." else "Basket ready. Review and pay in Uber Eats."
        return Playbook(steps, done)
    }
}
