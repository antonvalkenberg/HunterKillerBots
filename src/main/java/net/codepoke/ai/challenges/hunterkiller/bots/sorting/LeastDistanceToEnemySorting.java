package net.codepoke.ai.challenges.hunterkiller.bots.sorting;

import lombok.AllArgsConstructor;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MapLocation;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps.KnowledgeBase;
import net.codepoke.lib.util.datastructures.MatrixMap;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;

@AllArgsConstructor
public class LeastDistanceToEnemySorting
		implements ControlledObjectSortingStrategy {

	KnowledgeBase kb;

	String knowledgeLayer;

	@Override
	public IntArray sort(HunterKillerState state) {
		Player player = state.getActivePlayer();
		IntArray unitIDs = player.getUnitIDs();
		IntArray structureIDs = player.getStructureIDs();
		Map map = state.getMap();

		// Update the knowledge layer containing the distances to enemy units/structures
		kb.get(knowledgeLayer)
			.update(state);
		MatrixMap distanceMap = kb.get(knowledgeLayer)
									.getMap();

		Array<float[]> idDistance = new Array<float[]>();
		for (int i = 0; i < unitIDs.size; i++) {
			Unit unit = (Unit) map.getObject(unitIDs.get(i));
			MapLocation unitLocation = unit.getLocation();
			int distanceToEnemy = distanceMap.get(unitLocation.getX(), unitLocation.getY());
			idDistance.add(new float[] { unit.getID(), distanceToEnemy });
		}

		idDistance.sort((a, b) -> Float.compare(a[1], b[1]));
		IntArray sorting = new IntArray();
		for (int i = 0; i < idDistance.size; i++) {
			sorting.add((int) idDistance.get(i)[0]);
		}

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
