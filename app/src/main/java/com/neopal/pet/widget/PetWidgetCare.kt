package com.neopal.pet.widget

import android.content.Context
import com.neopal.pet.data.PetRepository
import com.neopal.pet.domain.ActionResult
import com.neopal.pet.domain.GameConfig
import com.neopal.pet.domain.PetState
import com.neopal.pet.domain.WidgetAction
import com.neopal.pet.domain.WidgetSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The running game, while there is one.
 *
 * This exists because of one seam that is easy to miss and impossible to see when it goes wrong.
 * The save is not the only copy of the pet: while the app is alive its view model holds the pet
 * it is running, and when the app comes back to the foreground it advances *that* copy and
 * writes it out. Anything the widget had written underneath it in the meantime is gone — no
 * error, no crash, just a meal the player served that the pet never ate.
 *
 * So the widget does not write the save while somebody else owns it. The game installs itself
 * here for as long as it is holding the pet, and a tap on the widget is handed to it instead.
 * With nothing installed — the app killed, or its view model cleared — the widget is the only
 * copy there is and writes it directly.
 *
 * Both halves live in one process, so this is a plain reference rather than anything clever: an
 * app widget provider is a broadcast receiver in the app's own process, and if that process is
 * gone then so is the view model this would have pointed at.
 */
interface PetWidgetHost {
    /** The pet the running game is holding, or null if it has none. */
    fun petInHand(): PetState?

    /** The config that game is running under. */
    fun configInHand(): GameConfig

    /**
     * Takes a result the widget produced and folds it into the running game, exactly as though
     * the player had done it on the pet screen. Called on the main thread.
     */
    fun takeFromWidget(result: ActionResult)
}

/** Where the running game says so. Null whenever nobody is holding the pet. */
object PetWidgetBridge {
    @Volatile
    var host: PetWidgetHost? = null
}

/**
 * One tap on the widget's chip.
 *
 * Nothing the widget drew is trusted. The verb crosses over; the state, the item and the whole
 * question of whether the thing is still worth doing are settled again against a fresh read —
 * see [WidgetSnapshot.apply]. The picture may be four hours old, and the pet may have been fed,
 * cleaned, put to bed or buried since it was drawn.
 */
internal object PetWidgetCare {

    /** True when the game accepted the action and the pet actually changed. */
    suspend fun perform(context: Context, action: WidgetAction): Boolean {
        if (action == WidgetAction.OPEN) return false
        val now = System.currentTimeMillis()

        if (PetWidgetBridge.host != null) {
            // The app is holding the pet. It applies the action and it writes the save; the
            // widget's only job here is to work out what the action was. Read once, on the
            // thread the game lives on: the view model can be cleared between the two.
            return withContext(Dispatchers.Main) {
                val live = PetWidgetBridge.host ?: return@withContext false
                val pet = live.petInHand() ?: return@withContext false
                val result = WidgetSnapshot.apply(pet, live.configInHand(), now, action)
                    ?: return@withContext false
                live.takeFromWidget(result)
                true
            }
        }

        val repository = PetRepository(context)
        val saved = repository.currentState() ?: return false
        val config = repository.currentConfig()
        val result = WidgetSnapshot.apply(saved, config, now, action) ?: return false
        // The result already carries the catch-up that was applied to reach it, so this is the
        // same write the app would have made on the same tick. Nothing else is running: if it
        // were, the branch above would have taken it.
        repository.save(result.state)
        return true
    }
}
