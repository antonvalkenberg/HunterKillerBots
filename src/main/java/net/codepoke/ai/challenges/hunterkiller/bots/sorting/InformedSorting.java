package net.codepoke.ai.challenges.hunterkiller.bots.sorting;

import java.util.HashMap;

import lombok.NoArgsConstructor;
import lombok.Setter;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.orders.HunterKillerOrder;
import net.codepoke.ai.challenges.hunterkiller.bots.HMCTSBot.SideInformation;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;

@NoArgsConstructor
public class InformedSorting
		implements ControlledObjectSortingStrategy {

	@Setter
	SideInformation information;

	@Override
	public IntArray sort(HunterKillerState state) {
		Map map = state.getMap();
		Player player = state.getActivePlayer();
		IntArray structureIDs = player.getStructureIDs();
		IntArray controlledIDs = new IntArray(player.getUnitIDs());

		// Only add structures that can spawn a unit
		for (int i = 0; i < structureIDs.size; i++) {
			Structure structure = (Structure) map.getObject(structureIDs.get(i));
			if (structure.canSpawnAUnit(state))
				controlledIDs.add(structureIDs.get(i));
		}

		Array<float[]> idUtility = new Array<float[]>();

		// Get the utility for all units
		for (int i = 0; i < controlledIDs.size; i++) {
			HashMap<HunterKillerOrder, SideInformation.OrderStatistics> info = information.getInformation(controlledIDs.get(i));

			float utility = 0;
			// If there is any info for this object, add all the average values together
			if (info != null) {
				for (SideInformation.OrderStatistics stats : info.values()) {
					utility += stats.getAverage();
				}
			}

			idUtility.add(new float[] { controlledIDs.get(i), utility });
		}

		IntArray sorting = new IntArray();

		// Sort units by whether or not they can attack
		idUtility.sort((a, b) -> Float.compare(a[1], b[1]));
		for (int i = 0; i < idUtility.size; i++) {
			sorting.add((int) idUtility.get(i)[0]);
		}

		return sorting;
	}

	@Override
	public void postProcess(IntArray currentOrdering, int nextDimension) {
	}

}
