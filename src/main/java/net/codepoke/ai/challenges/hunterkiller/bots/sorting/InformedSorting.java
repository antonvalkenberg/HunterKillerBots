package net.codepoke.ai.challenges.hunterkiller.bots.sorting;

import java.util.Collection;
import java.util.HashMap;

import lombok.NoArgsConstructor;
import lombok.Setter;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.orders.HunterKillerOrder;
import net.codepoke.ai.challenges.hunterkiller.bots.HMCTSBot.SideInformation;
import net.codepoke.ai.challenges.hunterkiller.bots.HMCTSBot.SideInformation.OrderStatistics;

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

		Array<double[]> idEntropy = new Array<double[]>();

		// Get the informed value for all units
		for (int i = 0; i < controlledIDs.size; i++) {
			HashMap<HunterKillerOrder, OrderStatistics> info = information.getInformation(controlledIDs.get(i));

			double informedValue = 0;
			if (info != null && info.size() > 0) {
				informedValue = entropy(info.values());
			}

			idEntropy.add(new double[] { controlledIDs.get(i), informedValue });
		}

		IntArray sorting = new IntArray();

		// Sort units by descending value
		idEntropy.sort((a, b) -> Double.compare(b[1], a[1]));
		for (int i = 0; i < idEntropy.size; i++) {
			sorting.add((int) idEntropy.get(i)[0]);
		}

		return sorting;
	}

	@Override
	public void postProcess(IntArray currentOrdering, int nextDimension) {
	}

	/**
	 * Calculates the (Shannon) entropy of a collection of {@link OrderStatistics}.
	 * Note: this method assumes the values in the statistics are normalized.
	 * 
	 * Resources used:
	 * http://mathworld.wolfram.com/Entropy.html
	 * https://en.wikipedia.org/wiki/Logarithm#Change_of_base
	 * 
	 * @param statistics
	 *            The collection of statistics
	 */
	public static double entropy(Collection<OrderStatistics> statistics) {
		// The (Shannon) entropy of a variable X is defined as:
		// H(X) = -1 * Sum-over-x(P(x) * Log_b(P(x)))
		// where b is the base of the logarithm (2 is said to be a commonly used value)
		// See http://mathworld.wolfram.com/Entropy.html

		// A logarithm with a base of b can also be computed as:
		// Log_b(P(x)) = Log_k(P(x)) / Log_k(b)
		// See https://en.wikipedia.org/wiki/Logarithm#Change_of_base

		// We make the assumption here that the average values for each order represent P(order)
		double sumOverOrder = 0;
		for (OrderStatistics orderStats : statistics) {
			double value = orderStats.getAverage();
			double log2value = Math.log10(value) / Math.log10(2);
			sumOverOrder += value * log2value;
		}

		return -1 * sumOverOrder;
	}

	/**
	 * Calculates the bias-corrected sample variance of a collection of {@link OrderStatistics}.
	 * Note: the size of the collection should be greater than 1, otherwise 0.0 is returned.
	 * 
	 * Resources used:
	 * http://mathworld.wolfram.com/Variance.html
	 * 
	 * @param statistics
	 *            The collection of statistics
	 */
	public static double sampleVariance(Collection<OrderStatistics> statistics) {
		if (statistics.size() <= 1)
			return 0.0;

		// The bias-corrected sample variance is defined as:
		// s^2_N-1 = (1 / N - 1) * Sum((x - sample_mean) ^ 2)
		// or
		// s^2_N-1 = Sum((x - sample_mean) ^ 2) / (N - 1)

		double sum = 0;
		for (OrderStatistics stats : statistics) {
			sum += stats.getAverage();
		}

		double sample_mean = sum / statistics.size();

		double sumDifferenceFromMeanSquared = 0;
		for (OrderStatistics stats : statistics) {
			double differenceFromMeanSquared = Math.pow(stats.getAverage() - sample_mean, 2);
			sumDifferenceFromMeanSquared += differenceFromMeanSquared;
		}

		double sample_variance = sumDifferenceFromMeanSquared / (statistics.size() - 1);

		return sample_variance;
	}

}
