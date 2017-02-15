package net.codepoke.ai.challenges.hunterkiller.bots;

import static net.codepoke.ai.challenges.hunterkiller.StreamExtensions.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MapLocation;
import net.codepoke.ai.challenge.hunterkiller.MoveGenerator;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.enums.Direction;
import net.codepoke.ai.challenge.hunterkiller.enums.UnitType;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.GameObject;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Wall;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Medic;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Soldier;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.StructureOrder;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;
import net.codepoke.ai.network.AIBot;
import one.util.streamex.StreamEx;

import com.badlogic.gdx.utils.Array;

/**
 * Represents an {@link AIBot} for the HunterKiller game that generates orders for it's structures and units by
 * following a simple rules-hierarchy.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class RulesBot
		extends AIBot<HunterKillerState, HunterKillerAction> {

	private static final boolean DEBUG_ImPossible = false;
	private static final boolean DEBUG_Fails = true;
	private static final Random r = new Random();
	HunterKillerRules rulesEngine = new HunterKillerRules();
	Array<Array<MapLocation>> unitPaths = new Array<Array<MapLocation>>(true, 5);

	public RulesBot() {
		super("", HunterKillerState.class, HunterKillerAction.class);
	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		// string builders for debugging
		StringBuilder possibleCheckFails = new StringBuilder();
		StringBuilder orderFailures = new StringBuilder();

		// Create an action
		HunterKillerAction rulesAction = new HunterKillerAction(state);

		// Make a copy of the state, so we can mutate it
		HunterKillerState copyState = state.copy();

		// Prepare the state for this player, this is a temporary hack to simulate the server-environment
		copyState.prepare(state.getActivePlayerID());
		Player player = copyState.getActivePlayer();
		Map map = copyState.getMap();

		// Maintain a counter on the amount of orders we create, to correctly set their index in the action
		int orderCounter = 0;

		// Get some things we'll need to access
		List<Structure> structures = player.getStructures(map);
		List<Unit> units = player.getUnits(map);
		List<Structure> enemyStructures = stream(map, Structure.class).filter(i -> i.isUnderControl() && !i.isControlledBy(player))
																		.toList();
		List<Unit> enemyUnits = stream(map, Unit.class).filter(i -> !i.isControlledBy(player))
														.toList();

		// Create orders for our structures
		RulesBot.createStructureOrders(	rulesEngine,
										rulesAction,
										orderCounter,
										structures,
										units,
										copyState,
										possibleCheckFails,
										orderFailures);

		// Go through our Units
		for (Unit unit : units) {
			// If we are a Medic and can use our heal ability
			if (unit instanceof Medic && unit.canUseSpecialAttack()) {
				// Check if there are any ally units within range that are damaged
				List<Unit> friendlyDamagedInRange = StreamEx.of(units)
															.filter(i -> unit.isWithinAttackRange(i.getLocation()) && i.isDamaged())
															.sortedByInt(i -> MapLocation.getManhattanDist(	unit.getLocation(),
																											i.getLocation()))
															.toList();
				if (!friendlyDamagedInRange.isEmpty()) {
					Unit damagedAlly = friendlyDamagedInRange.get(0);

					// Check if we can currently see the ally
					if (unit.getFieldOfView()
							.contains(damagedAlly.getLocation())) {
						// Create an order to heal the first unit in the list
						UnitOrder order = unit.attack(friendlyDamagedInRange.get(0), map, true);
						// Add the order if it's possible
						if (rulesEngine.addOrderIfPossible(rulesAction, orderCounter, copyState, order, possibleCheckFails, orderFailures)) {
							// Don't create another order for this unit
							continue;
						}
					}
					// If we can't see this unit, try to rotate towards it
					else {
						Direction directionToAlly = MapLocation.getDirectionTo(unit.getLocation(), damagedAlly.getLocation());
						// Check if any direction could be found, and we are not already facing that direction
						if (directionToAlly != null) {
							UnitOrder order = unit.rotate(Direction.rotationRequiredToFace(unit, directionToAlly));
							// Add the order if it's possible
							if (rulesEngine.addOrderIfPossible(	rulesAction,
																orderCounter,
																copyState,
																order,
																possibleCheckFails,
																orderFailures)) {
								// Don't create another order for this unit
								continue;
							}
						}

					}
				}
			}

			// Create a collection of enemies
			List<GameObject> enemies = new ArrayList<GameObject>(enemyUnits);
			enemies.addAll(enemyStructures);

			// Check if there are any enemy units within our field-of-view
			List<GameObject> enemiesInRange = StreamEx.of(enemies)
														.filter(i -> unit.isWithinAttackRange(i.getLocation()))
														.filter(i -> unit.getFieldOfView()
																			.contains(i.getLocation()))
														.toList();
			if (!enemiesInRange.isEmpty()) {

				// If we are a Soldier and can use our grenade ability
				if (unit instanceof Soldier && unit.canUseSpecialAttack()) {
					// Find the maximum units within the blast range (don't use a special for just 1 unit)
					long maxUnitsInBlastRange = 1;
					MapLocation targetLocation = null;

					for (GameObject enemy : enemiesInRange) {
						// Get the blast area of the attack, if targeted on this enemy
						List<MapLocation> aoe = map.getAreaAround(enemy.getLocation(), true);
						// Get the units in the blast area
						List<Unit> unitsInBlast = StreamEx.of(aoe)
															.filter(i -> map.getUnitAtLocation(i) != null)
															.map(i -> (Unit) map.getUnitAtLocation(i))
															.toList();

						// Check if there are any allies in the blast
						if (StreamEx.of(unitsInBlast)
									.anyMatch(i -> i.isControlledBy(player)))
							continue;

						// Count the number of enemy units within the area
						long enemiesInBlast = StreamEx.of(unitsInBlast)
														.filter(i -> !i.isControlledBy(player))
														.count();

						// Check if there are more enemies in this area than our current maximum
						if (enemiesInBlast > maxUnitsInBlastRange) {
							// Remember this new max and target
							maxUnitsInBlastRange = enemiesInBlast;
							targetLocation = enemy.getLocation();
						}
					}

					// Check if we found a target location
					if (targetLocation != null) {
						UnitOrder order = unit.attack(targetLocation, true);

						// Add the order if it's possible
						if (rulesEngine.addOrderIfPossible(rulesAction, orderCounter, copyState, order, possibleCheckFails, orderFailures)) {
							// Don't create another order for this unit
							continue;
						}
					}
				}

				// Attack any enemy unit
				GameObject enemy = enemiesInRange.get(0);
				UnitOrder order = unit.attack(enemy.getLocation(), false);
				// Add the order if it's possible
				if (rulesEngine.addOrderIfPossible(rulesAction, orderCounter, copyState, order, possibleCheckFails, orderFailures)) {
					// Don't create another order for this unit
					continue;
				}
			}

			// Rotate towards enemy unit
			Optional<Unit> closestEnemy = StreamEx.of(enemyUnits)
													.sortedBy(i -> MapLocation.getManhattanDist(unit.getLocation(), i.getLocation()))
													.findFirst();
			if (closestEnemy.isPresent()) {
				Direction directionToEnemy = MapLocation.getDirectionTo(unit.getLocation(), closestEnemy.get()
																										.getLocation());
				// Check if any direction could be found, and we are not already facing that direction
				if (directionToEnemy != null && unit.getOrientation() != directionToEnemy) {
					UnitOrder order = unit.rotate(Direction.rotationRequiredToFace(unit, directionToEnemy));
					// Add the order if it's possible
					if (rulesEngine.addOrderIfPossible(rulesAction, orderCounter, copyState, order, possibleCheckFails, orderFailures)) {
						// Don't create another order for this unit
						continue;
					}
				}
			}

			// Rotate away from a wall
			if (map.getFeatureAtLocation(map.getLocationInDirection(unit.getLocation(), unit.getOrientation(), 1)) instanceof Wall) {
				UnitOrder order = unit.rotate(r.nextBoolean());
				// Add the order if it's possible
				if (rulesEngine.addOrderIfPossible(rulesAction, orderCounter, copyState, order, possibleCheckFails, orderFailures)) {
					// Don't create another order for this unit
					continue;
				}
			}

			// Make sure we can accommodate a path for this unit
			if (unitPaths.size <= unit.getID())
				unitPaths.setSize(unit.getID() + 1);

			// Check if we are on a path
			if (unitPaths.get(unit.getID()) != null) {
				Array<MapLocation> path = unitPaths.get(unit.getID());
				boolean onPath = true;
				// Check if the first location on the path is our current location
				if (path.first()
						.equals(unit.getLocation())) {
					// Remove it
					path.removeIndex(0);
				}
				// Check if we are somewhere in the middle of the path
				else if (path.contains(unit.getLocation(), false)) {
					int cPos = path.indexOf(unit.getLocation(), false);
					// Remove up to and including
					path.removeRange(0, cPos);
				} else {
					onPath = false;
				}

				// If we are still on the path, move to the next location
				if (onPath && path.size > 0) {
					UnitOrder order = unit.move(MapLocation.getDirectionTo(unit.getLocation(), path.first()), map);
					// Add the order if it's possible
					if (rulesEngine.addOrderIfPossible(rulesAction, orderCounter, copyState, order, possibleCheckFails, orderFailures)) {
						// Don't create another order for this unit
						continue;
					}
				} else {
					// We lost the path somewhere, reset it
					unitPaths.set(unit.getID(), null);
				}
			} else {
				// Find the closest enemy structure
				Optional<Structure> closestEnemyStructure = StreamEx.of(enemyStructures)
																	.sortedBy(i -> MapLocation.getManhattanDist(unit.getLocation(),
																												i.getLocation()))
																	.findFirst();

				if (closestEnemyStructure.isPresent()) {
					// We can't plan a path to the structure itself, since it might not be walkable, so get any location
					// next to it
					List<MapLocation> nextToStructure = map.getAreaAround(closestEnemyStructure.get()
																								.getLocation(), false);
					Optional<MapLocation> closestToTargetLocation = StreamEx.of(nextToStructure)
																			.sortedByInt(i -> MapLocation.getManhattanDist(	unit.getLocation(),
																															i))
																			.findFirst();
					// If we found any location close to our target
					if (closestToTargetLocation.isPresent()) {
						Array<MapLocation> path = map.findPath(unit.getLocation(), closestToTargetLocation.get());
						// Check if anything was found
						if (path.size > 0) {

							// Remember the path
							unitPaths.set(unit.getID(), path);
							// Move to the first location
							UnitOrder order = unit.move(MapLocation.getDirectionTo(unit.getLocation(), path.first()), map);
							// Add the order if it's possible
							if (rulesEngine.addOrderIfPossible(	rulesAction,
																orderCounter,
																copyState,
																order,
																possibleCheckFails,
																orderFailures)) {
								// Don't create another order for this unit
								continue;
							}
						}
					}
				}

			}

			// Random walk
			List<UnitOrder> moveOrders = MoveGenerator.getAllLegalMoveOrders(copyState, unit);
			if (!moveOrders.isEmpty()) {
				UnitOrder order = moveOrders.get(r.nextInt(moveOrders.size()));
				// Add the order if it's possible
				if (rulesEngine.addOrderIfPossible(rulesAction, orderCounter, copyState, order, possibleCheckFails, orderFailures)) {
					// Don't create another order for this unit
					continue;
				}
			}
		}

		if (DEBUG_ImPossible && possibleCheckFails.length() > 0) {
			System.out.printf(	"RB(%d)R(%d)T(%d): some orders not possible, Reasons:%n%s%n",
								player.getID(),
								state.getCurrentRound(),
								state.getMap().currentTick,
								possibleCheckFails.toString());
		}
		if (DEBUG_Fails && orderFailures.length() > 0) {
			System.out.printf(	"RB(%d)R(%d)T(%d): some orders failed, Reasons:%n%s%n",
								player.getID(),
								state.getCurrentRound(),
								state.getMap().currentTick,
								orderFailures.toString());
		}

		return rulesAction;
	}

	/**
	 * Adds an order for each structure in the provided list, if that structure could spawn a unit in the provided
	 * state. This method attempts to balance the types of units. Note that it also tries to execute the order on the
	 * provided state to determine if the order would succeed.
	 * 
	 * @param rules
	 *            The current rules being used for the game.
	 * @param action
	 *            The action that is being created.
	 * @param orderIndex
	 *            The index that the next order should have in the action's list of orders.
	 * @param structures
	 *            The structures the player is currently controlling.
	 * @param units
	 *            The units the player is currently controlling.
	 * @param stateCopy
	 *            The state of the game.
	 * @param possibleCheckFails
	 *            A way to collect any error/failure messages received from the rules during the check to see if an
	 *            order is possible.
	 * @param orderFails
	 *            A way to collect any error/failure messages received from executing the created orders on the provided
	 *            state.
	 */
	public static void createStructureOrders(HunterKillerRules rules, HunterKillerAction action, int orderIndex,
			List<Structure> structures, List<Unit> units, HunterKillerState stateCopy, StringBuilder possibleCheckFails,
			StringBuilder orderFails) {
		// Go through our structures
		for (Structure structure : structures) {
			// Check if the structure can spawn anything in this state
			if (structure.canSpawn(stateCopy)) {
				// Spawn a type we don't have yet, or have the least of
				long soldierCount = units.stream()
											.filter(i -> i.getType() == UnitType.Soldier)
											.count();
				long medicCount = units.stream()
										.filter(i -> i.getType() == UnitType.Medic)
										.count();
				long infectedCount = units.stream()
											.filter(i -> i.getType() == UnitType.Infected)
											.count();

				boolean spawnUnit = false;
				UnitType spawnType = null;
				// Check infected first, these have highest priority
				if (infectedCount <= medicCount && infectedCount <= soldierCount) {
					spawnUnit = true;
					spawnType = UnitType.Infected;
				}
				// Next, check medics
				else if (medicCount <= soldierCount && medicCount <= infectedCount) {
					spawnUnit = true;
					spawnType = UnitType.Medic;
				}
				// Check soldiers
				else if (soldierCount <= medicCount && soldierCount <= infectedCount) {
					spawnUnit = true;
					spawnType = UnitType.Soldier;
				}

				// Check if we want to spawn a unit, have set the type we want, and if we can execute an order of that
				// type
				if (spawnUnit && spawnType != null && structure.canSpawn(stateCopy, spawnType)) {
					// Order the spawning of a medic
					StructureOrder order = structure.spawn(spawnType);
					// Add the order if it's possible
					if (rules.addOrderIfPossible(action, orderIndex, stateCopy, order, possibleCheckFails, orderFails)) {
						// Don't create another order for this object
						continue;
					}
				}
			}
		}
	}

}
