package net.codepoke.ai.challenges.hunterkiller.bots.sorting;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MoveGenerator;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;

public class AttackSorting
		implements ControlledObjectSortingStrategy {

	@Override
	public IntArray sort(HunterKillerState state) {
		Player player = state.getActivePlayer();
		IntArray unitIDs = player.getUnitIDs();
		IntArray structureIDs = player.getStructureIDs();
		Map map = state.getMap();

		// Shuffle collections, to prevent equal things being added in the same ways
		unitIDs.shuffle();
		structureIDs.shuffle();

		Array<float[]> idCanAttack = new Array<float[]>();
		for (int i = 0; i < unitIDs.size; i++) {
			Unit unit = (Unit) map.getObject(unitIDs.get(i));

			boolean canAttack = false;
			// Check if the unit can use its special attack
			if (unit.canUseSpecialAttack()) {
				UnitOrder order = MoveGenerator.getRandomAttackOrder(state, unit, false, true);
				canAttack = order != null;
			}
			UnitOrder order = MoveGenerator.getRandomAttackOrder(state, unit, false, false);
			canAttack = order != null;

			idCanAttack.add(new float[] { unit.getID(), canAttack ? 1 : 0 });
		}

		IntArray sorting = new IntArray();

		// Sort units by whether or not they can attack
		// Note: descending because canAttack = 1, can't is 0
		idCanAttack.sort((a, b) -> Float.compare(b[1], a[1]));
		for (int i = 0; i < idCanAttack.size; i++) {
			sorting.add((int) idCanAttack.get(i)[0]);
		}

		// Structures can't attack, so those are considered last.
		for (int i = 0; i < structureIDs.size; i++) {
			Structure structure = (Structure) map.getObject(structureIDs.get(i));
			if (structure.canSpawnAUnit(state))
				sorting.add(structure.getID());
		}

		return sorting;
	}

	@Override
	public void postProcess(IntArray currentOrdering, int nextDimension) {
	}

}
