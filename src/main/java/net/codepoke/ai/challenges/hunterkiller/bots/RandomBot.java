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
import net.codepoke.ai.network.AIBot;

/**
 * Represents an {@link AIBot} for the HunterKiller game that generates random orders for it's structures and units.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class RandomBot
		extends BaseBot<HunterKillerState, HunterKillerAction> {

	private static Random r = new Random();
	private static final String myUID = "mbu9rbe4eplj6iuh96nqtspdd0";

	@Getter
	public final String botName = "RandomBot";

	private static final double noUnitOrderThreshold = 0.2;
	private static final double noBaseOrderThreshold = 0.1;

	public RandomBot() {
		super(myUID, HunterKillerState.class, HunterKillerAction.class);
	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		// Check if we need to wait
		waitTimeBuffer();

		// Create a random action
		return createRandomAction(state);
	}

	/**
	 * Returns an {@link HunterKillerAction Action} containing randomly selected order for each structure and unit that
	 * the player controls. Note that there is a preset chance of no order being created, these are defined by
	 * {@link RandomBot#noBaseOrderThreshold} and {@link RandomBot#noUnitOrderThreshold}.
	 * 
	 * @param state
	 *            The current game state.
	 */
	public static HunterKillerAction createRandomAction(HunterKillerState state) {
		// Create a random action
		HunterKillerAction random = new HunterKillerAction(state);

		// Get some objects we need to query
		Player player = state.getActivePlayer();
		Map map = state.getMap();

		// Move through all structure
		for (Structure structure : player.getStructures(map)) {
			// Check if we want to do nothing
			if (r.nextDouble() <= noBaseOrderThreshold)
				continue;

			// Add a random order for this structure to the action
			StructureOrder order = createRandomOrder(structure, state);
			if (order != null)
				random.addOrder(order);
		}

		// Move through all Units
		for (Unit unit : player.getUnits(map)) {
			// Check if we want to do nothing
			if (r.nextDouble() <= noUnitOrderThreshold)
				continue;

			// Add a random order for this unit to the action
			UnitOrder order = createRandomOrder(unit, state);
			if (order != null)
				random.addOrder(order);
		}

		// Return the randomly created action
		return random;
	}

	/**
	 * Returns a randomly selected order from the collection of available legal orders. Note that this method will
	 * return null if the structure has no legal orders available.
	 * 
	 * @param structure
	 *            The structure to create an order for.
	 * @param state
	 *            The current state of the game.
	 */
	public static StructureOrder createRandomOrder(Structure structure, HunterKillerState state) {
		// Get all legal orders for this structure
		List<StructureOrder> legalOrders = MoveGenerator.getAllLegalOrders(state, structure);

		// Add a random order
		if (!legalOrders.isEmpty()) {
			return legalOrders.get(r.nextInt(legalOrders.size()));
		}

		// Return null if the structure has no legal orders available
		return null;
	}

	/**
	 * Returns a randomly selected order from the collection of available legal orders. Note that this method will
	 * return null if the unit has no legal orders available.
	 * 
	 * @param unit
	 *            The unit to create an order for.
	 * @param state
	 *            The current state of the game.
	 */
	public static UnitOrder createRandomOrder(Unit unit, HunterKillerState state) {
		Map map = state.getMap();

		// Get all legal rotation orders for this unit
		List<UnitOrder> legalRotationOrders = MoveGenerator.getAllLegalOrders(state, unit, true, false, false);
		// Get all legal move orders for this unit
		List<UnitOrder> legalMoveOrders = MoveGenerator.getAllLegalOrders(state, unit, false, true, false);
		// Get all legal attack orders for this unit
		List<UnitOrder> legalAttackOrders = MoveGenerator.getAllLegalOrders(state, unit, false, false, true);

		// Filter out any friendly-fire attacks
		filterFriendlyFire(legalAttackOrders, unit, map);

		double attackType = r.nextDouble();
		// Do a random rotation with 20% chance
		if (attackType <= 0.2 && !legalRotationOrders.isEmpty()) {
			return legalRotationOrders.get(r.nextInt(legalRotationOrders.size()));
		}
		// Do a random move with 50% chance
		else if (attackType <= 0.7 && !legalMoveOrders.isEmpty()) {
			return legalMoveOrders.get(r.nextInt(legalMoveOrders.size()));
		}
		// Do a random attack with 30% chance
		else if (!legalAttackOrders.isEmpty()) {
			return legalAttackOrders.get(r.nextInt(legalAttackOrders.size()));
		}

		return null;
	}

}
