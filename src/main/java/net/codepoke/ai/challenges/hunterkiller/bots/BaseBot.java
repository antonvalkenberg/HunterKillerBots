package net.codepoke.ai.challenges.hunterkiller.bots;

import java.util.List;
import java.util.Random;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.MoveGenerator;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.StructureOrder;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;
import net.codepoke.ai.network.AIBot;

public abstract class BaseBot<S, A>
		extends AIBot<HunterKillerState, HunterKillerAction> {

	public BaseBot(String botUID, Class<HunterKillerState> state, Class<HunterKillerAction> action) {
		super(botUID, state, action);
	}

	private static Random r = new Random();

	public static final int NOT_SET_TIME_BUFFER = -1;

	public static int TIME_BUFFER_MS = NOT_SET_TIME_BUFFER;

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

}
