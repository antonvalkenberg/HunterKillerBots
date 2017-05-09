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

		// Only consider structures that can spawn a unit
		for (int i = 0; i < structureIDs.size; i++) {
			Structure structure = (Structure) map.getObject(structureIDs.get(i));
			if (structure.canSpawnAUnit(state))
				controlledIDs.add(structureIDs.get(i));
		}

		// Shuffle collection, to prevent equal things being added in the same ways
		controlledIDs.shuffle();

		Array<float[]> idEntropy = new Array<float[]>();

		// Get the utility for all units
		for (int i = 0; i < controlledIDs.size; i++) {
			HashMap<HunterKillerOrder, SideInformation.OrderStatistics> info = information.getInformation(controlledIDs.get(i));

			// TODO: use entropy instead of average-utility sum
			// Maximum difference to average?
			// variance -> the average of the squared differences from the mean (mean = average)

			float variance = 0;
			float maxDifference = 0;
			// If there is any info for this object, add all the average values together
			if (info != null && info.size() > 0) {
				float total = 0;
				for (SideInformation.OrderStatistics stats : info.values()) {
					total += stats.getAverage();
				}
				float mean = total / info.size();
				float totalSquaredDifference = 0;
				for (SideInformation.OrderStatistics stats : info.values()) {
					double differenceFromMeanSquared = Math.pow(mean - stats.getAverage(), 2);
					totalSquaredDifference += differenceFromMeanSquared;
					if (differenceFromMeanSquared > maxDifference) {
						maxDifference = (float) differenceFromMeanSquared;
					}
				}
				variance = totalSquaredDifference / info.size();
			}

			// idEntropy.add(new float[] { controlledIDs.get(i), maxDifference });
			idEntropy.add(new float[] { controlledIDs.get(i), variance });
		}

		IntArray sorting = new IntArray();

		// Sort units by descending variance
		idEntropy.sort((a, b) -> Float.compare(b[1], a[1]));
		for (int i = 0; i < idEntropy.size; i++) {
			sorting.add((int) idEntropy.get(i)[0]);
		}

		return sorting;
	}

	@Override
	public void postProcess(IntArray currentOrdering, int nextDimension) {
	}

}
