package net.codepoke.ai.challenges.hunterkiller.bots;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MoveGenerator;
import net.codepoke.ai.challenge.hunterkiller.enums.UnitOrderType;
import net.codepoke.ai.challenge.hunterkiller.enums.UnitType;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.StructureOrder;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;
import net.codepoke.ai.network.AIBot;

public abstract class BaseBot<S, A>
		extends AIBot<HunterKillerState, HunterKillerAction> {

	public BaseBot(String botUID, Class<HunterKillerState> state, Class<HunterKillerAction> action) {
		super(botUID, state, action);
		// Check if any UID is set, otherwise create a temporary one
		if (botUID.isEmpty())
			this.setBotUID(UUID.randomUUID()
								.toString());
	}

	private static Random r = new Random();

	public static final int NOT_SET_TIME_BUFFER = -1;

	public static int TIME_BUFFER_MS = NOT_SET_TIME_BUFFER;

	public abstract String getBotName();

	public abstract HunterKillerAction handle(HunterKillerState state);

	public static void setTimeBuffer(int timeBuffer) {
		TIME_BUFFER_MS = timeBuffer;
	}

	public static void waitTimeBuffer() {
		// Check if we need to adhere to a set time buffer
		if (TIME_BUFFER_MS != NOT_SET_TIME_BUFFER) {
			try {
				Thread.sleep(TIME_BUFFER_MS);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	public static UnitOrder getRandomOrder(HunterKillerState state, Unit unit) {
		// Get all legal orders for this unit
		List<UnitOrder> legalOrders = MoveGenerator.getAllLegalOrders(state, unit);
		// Prune some orders we do not want to investigate
		RandomBot.filterFriendlyFire(legalOrders, unit, state.getMap());
		// Return a random order, if there are any available
		if (!legalOrders.isEmpty()) {
			return legalOrders.get(r.nextInt(legalOrders.size()));
		}
		return null;
	}

	public static StructureOrder getRandomOrder(HunterKillerState state, Structure structure) {
		// Get all legal orders for this structure
		List<StructureOrder> legalOrders = MoveGenerator.getAllLegalOrders(state, structure);
		// Return a random order, if there are any available
		if (!legalOrders.isEmpty()) {
			return legalOrders.get(r.nextInt(legalOrders.size()));
		}
		return null;
	}

	/**
	 * Filters out any attacks that are considered 'Friendly Fire', e.g. attacking one's own structure or unit. This
	 * method also filters out any attack order without a proper target, i.e. attacking a location without a Unit or
	 * Structure.
	 * 
	 * @param orders
	 *            The orders to filter.
	 * @param unit
	 *            The Unit for which the orders are created.
	 * @param map
	 *            The current game state.
	 */
	public static void filterFriendlyFire(List<UnitOrder> orders, Unit unit, Map map) {
		orders.removeIf((order) -> {
			// Skip non-attack orders
			if (!order.isAttackOrder())
				return false;
			// Remove all attacks without a proper target
			if (order.isAttackOrderWithoutTarget(unit, map))
				return true;
			// Remove all attacks with our own location as target
			if (order.getTargetLocation()
						.equals(unit.getLocation()))
				return true;
			// Remove all attack with an ally base as target
			if (order.isAttackOrderTargetingAllyBase(unit, map))
				return true;
			// Remove all attacks with an ally unit as target
			if (order.isAttackOrderTargetingAllyUnit(unit, map)) {
				// Unless the order is a for an Infected, or a Medic's special attack
				return unit.getType() != UnitType.Infected
						&& !(order.getUnitType() == UnitType.Medic && order.getOrderType() == UnitOrderType.ATTACK_SPECIAL);
			}
			// Other orders are OK
			return false;
		});
	}

}
