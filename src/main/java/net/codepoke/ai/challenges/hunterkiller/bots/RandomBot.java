package net.codepoke.ai.challenges.hunterkiller.bots;

import java.util.List;
import java.util.Random;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MoveGenerator;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.enums.UnitOrderType;
import net.codepoke.ai.challenge.hunterkiller.enums.UnitType;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.MapFeature;
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
		extends AIBot<HunterKillerState, HunterKillerAction> {

	private static Random r = new Random();
	private static final double noUnitOrderThreshold = 0.2;
	private static final double noBaseOrderThreshold = 0.1;

	public RandomBot() {
		super("", HunterKillerState.class, HunterKillerAction.class);
	}

	public HunterKillerAction handle(HunterKillerState state) {
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
		Player player = state.getPlayer(state.getCurrentPlayer());
		Map map = state.getMap();

		// Move through all structure
		for (Structure structure : player.getStructures(state.getMap())) {
			// Check if we want to do nothing
			if (r.nextDouble() <= noBaseOrderThreshold)
				continue;

			// Get all legal orders for this structure
			List<StructureOrder> legalOrders = MoveGenerator.getAllLegalOrders(state, structure);

			// Add a random order
			if (!legalOrders.isEmpty()) {
				random.addOrder(legalOrders.get(r.nextInt(legalOrders.size())));
			}
		}

		// Move through all Units
		for (Unit unit : player.getUnits(state.getMap())) {
			// Check if we want to do nothing
			if (r.nextDouble() <= noUnitOrderThreshold)
				continue;

			// Get all legal rotation orders for this unit
			List<UnitOrder> legalRotationOrders = MoveGenerator.getAllLegalOrders(state, unit, true, false, false);
			// Get all legal move orders for this unit
			List<UnitOrder> legalMoveOrders = MoveGenerator.getAllLegalOrders(state, unit, false, true, false);
			// Get all legal attack orders for this unit
			List<UnitOrder> legalAttackOrders = MoveGenerator.getAllLegalOrders(state, unit, false, false, true);

			// Remove all attacks without a proper target
			legalAttackOrders.removeIf((order) -> {
				Unit target = map.getUnitAtLocation(order.getTargetLocation());
				MapFeature feature = map.getFeatureAtLocation(order.getTargetLocation());
				return target == null && !(feature instanceof Structure);
			});

			// Remove all attack with an ally base as target
			legalAttackOrders.removeIf(order -> {
				MapFeature feature = map.getFeatureAtLocation(order.getTargetLocation());
				return feature instanceof Structure && ((Structure) feature).getControllingPlayerID() == unit.getControllingPlayerID();
			});

			// Remove all attacks with an ally unit as target, unless the order is a Medic's special attack
			legalAttackOrders.removeIf(order -> {
				Unit target = map.getUnitAtLocation(order.getTargetLocation());
				return target != null && target.getControllingPlayerID() == unit.getControllingPlayerID()
						&& !(order.getUnitType() == UnitType.Medic && order.getOrderType() == UnitOrderType.ATTACK_SPECIAL);
			});

			double attackType = r.nextDouble();
			// Do a random rotation with 20% chance
			if (attackType <= 0.2 && !legalRotationOrders.isEmpty()) {
				random.addOrder(legalRotationOrders.get(r.nextInt(legalRotationOrders.size())));
			}
			// Do a random move with 50% chance
			else if (attackType <= 0.7 && !legalMoveOrders.isEmpty()) {
				random.addOrder(legalMoveOrders.get(r.nextInt(legalMoveOrders.size())));
			}
			// Do a random attack with 30% chance
			else if (!legalAttackOrders.isEmpty()) {
				random.addOrder(legalAttackOrders.get(r.nextInt(legalAttackOrders.size())));
			}
		}

		// Return the randomly created action
		return random;
	}

}
