package net.codepoke.ai.challenges.hunterkiller.bots;

import lombok.Getter;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MoveGenerator;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.StructureOrder;

public class ShortCircuitRandomBot
		extends BaseBot<HunterKillerState, HunterKillerAction> {

	@Getter
	public final String botName = "ShortCircuitRandomBot";

	private static final String myUID = "";

	public ShortCircuitRandomBot() {
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
			StructureOrder legalOrder = MoveGenerator.getRandomOrder(state, structure);
			// Add a random order, if there are any available
			if (legalOrder != null) {
				randomAction.addOrder(legalOrder);
			}
		}

		// Do the same for all units
		for (Unit unit : player.getUnits(map)) {
			randomAction.addOrder(MoveGenerator.getRandomOrder(state, unit));
		}

		return randomAction;
	}

}
