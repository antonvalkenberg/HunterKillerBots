package net.codepoke.ai.challenges.hunterkiller.bots;

import static net.codepoke.ai.challenges.hunterkiller.StreamExtensions.stream;
import static net.codepoke.lib.util.ai.search.SolutionStrategy.Util.SOLUTION_NODE;
import static net.codepoke.lib.util.ai.search.graph.GraphSearch.breadthFirst;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import lombok.AllArgsConstructor;
import lombok.Data;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MoveGenerator;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.enums.UnitOrderType;
import net.codepoke.ai.challenge.hunterkiller.enums.UnitType;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.GameObject;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.MapFeature;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.StructureOrder;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;
import net.codepoke.ai.challenges.hunterkiller.HunterKillerVisualization;
import net.codepoke.ai.network.AIBot;
import net.codepoke.lib.util.ai.SearchContext;
import net.codepoke.lib.util.datastructures.MatrixMap;
import net.codepoke.lib.util.datastructures.MatrixMap.MatrixExpansionStrategy;
import net.codepoke.lib.util.datastructures.Path;
import one.util.streamex.StreamEx;

import com.badlogic.gdx.graphics.Color;

/**
 * Represents a bot used for testing purposes.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class TestBot
		extends AIBot<HunterKillerState, HunterKillerAction> {

	private static Random r = new Random();
	private static final double noUnitOrderThreshold = 0.2;
	private static final double noBaseOrderThreshold = 0.1;

	HunterKillerVisualization visualisation;

	public TestBot(HunterKillerVisualization vis) {
		super("", HunterKillerState.class, HunterKillerAction.class);
		visualisation = vis;
	}

	@Data
	@AllArgsConstructor
	private static class Point {

		int location;
		int value;

	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		Player player = state.getActivePlayer();
		Map map = state.getMap();

		MatrixMap valueMap = new MatrixMap(map.getMapWidth(), map.getMapHeight());
		valueMap.reset(-1);

		// Domain, Position, Action, Subject, ...
		SearchContext<MatrixMap, Point, Point, GameObject, Path<Point>> context = new SearchContext<>();

		context.domain(valueMap);
		context.search(breadthFirst(SOLUTION_NODE));
		context.goal((c, current) -> false); // Try to visit everything
		context.expansion((c, current) -> {
			// Get all options from matrixmap
			Iterable<Integer> options = MatrixExpansionStrategy.EXPANSION_ORTHOGONAL.expand(c, current.location);

			// add +1 to value
			int nextValue = current.value + 1;

			// filter on not-blocked
			StreamEx<Integer> expandables = StreamEx.of(options.iterator())
													// We block for those that have a wall
													.filter(i -> {
														MapFeature feature = map.getFeatureAtLocation(map.toLocation(i));
														return !feature.isBlockingLOS();
													})
													// We allow if the value on the matrix is higher
													.filter(i -> {
														int val = c.domain()
																	.get(i);
														return val == -1 || val > nextValue;
													});

			// set value in matrixmap, if it's not lower or equal to something already there
			List<Point> returnList = new ArrayList<Point>();
			for (Integer number : expandables) {
				c.domain()
					.set(number, nextValue);

				returnList.add(new Point(number, nextValue));
			}

			return returnList;
		});

		List<Structure> enemyStructures = stream(map, Structure.class).filter(i -> i.isUnderControl() && !i.isControlledBy(player))
																		.toList();

		for (Structure structure : enemyStructures) {

			context.source(new Point(map.toPosition(structure.getLocation()), 0));

			context.execute();
		}

		// System.out.println(valueMap);
		int[] valueRange = valueMap.findRange();

		float[][] values = new float[map.getMapWidth()][map.getMapHeight()];

		for (int x = 0; x < map.getMapWidth(); x++) {
			for (int y = 0; y < map.getMapHeight(); y++) {

				int mapLoc = valueMap.convert(x, y);

				values[x][y] = valueMap.getNormalized(mapLoc, valueRange);
			}
		}

		// System.out.println(valueMap);

		// Set the value array into the visualisation
		visualisation.visualise(values, Color.GREEN, Color.BLUE, Color.RED);

		// Create a random action to return
		return createRandomAction(state);
	}

	private HunterKillerAction createRandomAction(HunterKillerState state) {
		// Create a random action
		HunterKillerAction random = new HunterKillerAction(state);
		Player player = state.getPlayer(state.getCurrentPlayer());

		// Move through all structure
		for (Structure structure : player.getStructures(state.getMap())) {
			if (r.nextDouble() <= noBaseOrderThreshold)
				continue;

			// Get all legal orders for this structure
			List<StructureOrder> legalOrders = MoveGenerator.getAllLegalOrders(state, structure);

			// Add a random order
			if (!legalOrders.isEmpty()) {
				random.addOrder(legalOrders.get(r.nextInt(legalOrders.size())));
			}
		}

		Map map = state.getMap();

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
