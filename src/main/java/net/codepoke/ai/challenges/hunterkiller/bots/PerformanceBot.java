/**
 * 
 */
package net.codepoke.ai.challenges.hunterkiller.bots;

import java.util.List;
import java.util.Random;

import lombok.Getter;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MoveGenerator;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.StructureOrder;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;

/**
 * Bot for testing performance.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class PerformanceBot
		extends BaseBot<HunterKillerState, HunterKillerAction> {

	@Getter
	public final String botName = "RandomBot";
	private static Random r = new Random();
	private static final String myUID = "";

	public PerformanceBot() {
		super(myUID, HunterKillerState.class, HunterKillerAction.class);
	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		// Create an action which will contain a random order for each structure and unit we control
		HunterKillerAction randomAction = new HunterKillerAction(state);

		Player player = state.getActivePlayer();
		Map map = state.getMap();

		// Go through all structures, and pick a random order out of all legal orders.
		for (Structure structure : player.getStructures(map)) {
			// Get all legal orders for this structure
			List<StructureOrder> legalOrders = MoveGenerator.getAllLegalOrders(state, structure);
			// Add a random order, if there are any available
			if (!legalOrders.isEmpty()) {
				randomAction.addOrder(legalOrders.get(r.nextInt(legalOrders.size())));
			}
		}

		// Do the same for all units
		for (Unit unit : player.getUnits(map)) {
			// Get all legal orders for this unit
			List<UnitOrder> legalOrders = MoveGenerator.getAllLegalOrders(state, unit);
			// Add a random order, if there are any available
			if (!legalOrders.isEmpty()) {
				randomAction.addOrder(legalOrders.get(r.nextInt(legalOrders.size())));
			}
		}

		return randomAction;
	}

}
