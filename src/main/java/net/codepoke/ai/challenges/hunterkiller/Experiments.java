package net.codepoke.ai.challenges.hunterkiller;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import net.codepoke.ai.challenges.hunterkiller.bots.BaseBot;
import net.codepoke.ai.challenges.hunterkiller.bots.HMCTSBot;
import net.codepoke.ai.challenges.hunterkiller.bots.LSIBot;
import net.codepoke.ai.challenges.hunterkiller.bots.NMCBot;
import net.codepoke.ai.challenges.hunterkiller.bots.ShortCircuitRandomBot;
import net.codepoke.ai.challenges.hunterkiller.bots.SquadBot;
import net.codepoke.ai.challenges.hunterkiller.bots.sorting.AttackSorting;
import net.codepoke.ai.challenges.hunterkiller.bots.sorting.ControlledObjectSortingStrategy;
import net.codepoke.ai.challenges.hunterkiller.bots.sorting.InformedSorting;
import net.codepoke.ai.challenges.hunterkiller.bots.sorting.LeastDistanceToEnemySorting;
import net.codepoke.ai.challenges.hunterkiller.bots.sorting.RandomSorting;
import net.codepoke.ai.challenges.hunterkiller.bots.sorting.StaticSorting;
import net.codepoke.ai.challenges.hunterkiller.tournament.MatchData;
import net.codepoke.ai.challenges.hunterkiller.tournament.TournamentMatch;
import net.codepoke.lib.util.common.Stopwatch;

import org.paukov.combinatorics.Factory;
import org.paukov.combinatorics.Generator;
import org.paukov.combinatorics.ICombinatoricsVector;

import com.badlogic.gdx.utils.Array;

/**
 * Experiments as conducted during my internship @CodePoKE.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class Experiments {

	public static final String BASE_PATH = System.getProperty("user.home") + "/";
	public static final String BASE_FILE_EXTENSION = ".txt";

	public static void main(String[] arg) {
		// runDimensionalOrdering(200, true);
		// runGrandTournament(200);
		runPlayoutStrategies(100);
		// runCTest(30);
	}

	public static void runCTest(int numberOfGames) {
		// Test values between 0.0 and 1.0 inclusive, incrementing by 0.1
		// for (int i = 0; i < 11; i++) {
		// double c = (i / 10.0);
		// testC(numberOfGames, c);
		// }

		// Test values between 0.0 and 2.0 inclusive, incrementing by 0.01
		// for (int i = 0; i < 21; i++) {
		// double c = (i / 100.0);
		// testC(numberOfGames, c);
		// }

		// Test values between 5.0 and 8.0 exclusive, incrementing by 0.01
		// for (int i = 1; i < 30; i++) {
		// double c = ((i + 50) / 100.0);
		// testC(numberOfGames, c);
		// }
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static void runDimensionalOrdering(int numberOfGames, boolean useSideInformation) {
		// Use Supplier here to create a new instance when required in the parallel threads
		Supplier<RandomSorting> randomSort = () -> new RandomSorting();
		Supplier<StaticSorting> staticSort = () -> new StaticSorting();
		Supplier<LeastDistanceToEnemySorting> enemySort = () -> new LeastDistanceToEnemySorting();
		Supplier<AttackSorting> attackSort = () -> new AttackSorting();
		Supplier<InformedSorting> informedSort = () -> new InformedSorting();

		Array<Supplier<? extends ControlledObjectSortingStrategy>> sortingStrats = Array.with(randomSort, staticSort, enemySort, attackSort);
		if (useSideInformation)
			sortingStrats.add(informedSort);

		// Create a combinatorics vector based on the strategy array
		ICombinatoricsVector<Supplier<? extends ControlledObjectSortingStrategy>> initialVector = Factory.createVector(sortingStrats.toArray());

		// Do a test for all combinations of 2 strategies
		Generator<Supplier<? extends ControlledObjectSortingStrategy>> generator = Factory.createSimpleCombinationGenerator(initialVector,
																															2);
		for (ICombinatoricsVector<Supplier<? extends ControlledObjectSortingStrategy>> combination : generator) {

			Array<BaseBot> botsSetup = Array.with(	new HMCTSBot(useSideInformation, combination.getValue(0)
																								.get(), new ShortCircuitRandomBot()),
													new HMCTSBot(useSideInformation, combination.getValue(1)
																								.get(), new ShortCircuitRandomBot()));

			String bot0Name = botsSetup.get(0)
										.getBotName();
			String bot1Name = botsSetup.get(1)
										.getBotName();

			// Create an array to store results
			int[] botWins = new int[] { 0, 0 };

			String fileName = bot0Name + " VS " + bot1Name;
			// Check if the filename already exists, if so, skip this combination
			if (fileExists(fileName))
				continue;

			// Write the combination to a file
			writeToFile("(" + numberOfGames + " games): " + combination, fileName);

			// This sets the system to use 2 cores
			System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "2");

			long totalTime = IntStream.range(0, numberOfGames)
										.parallel()
										.mapToLong(i -> {

											Array<BaseBot> bots = Array.with(	new HMCTSBot(useSideInformation, combination.getValue(0)
																															.get(),
																								new ShortCircuitRandomBot()),
																				new HMCTSBot(useSideInformation, combination.getValue(1)
																															.get(),
																								new ShortCircuitRandomBot()));

											TournamentMatch game = new TournamentMatch(bots.get(0), bots.get(1));

											Stopwatch gameTimer = new Stopwatch();
											gameTimer.start();

											game.runGame(i);

											long time = gameTimer.end();

											MatchData bot0Data = game.getBot0Data();
											writeToFile(bot0Data.toString(), fileName);
											if (bot0Data.botName.equals(bot0Name) && bot0Data.botRank == 0)
												botWins[0]++;
											MatchData bot1Data = game.getBot1Data();
											writeToFile(bot1Data.toString(), fileName);
											if (bot1Data.botName.equals(bot1Name) && bot1Data.botRank == 0)
												botWins[1]++;

											return time;
										})
										.sum();

			System.out.println("All games took " + TimeUnit.MILLISECONDS.convert(totalTime, TimeUnit.NANOSECONDS) + " miliseconds");
			writeToFile("Bot '" + bot0Name + "' won " + botWins[0] + " games.", fileName);
			writeToFile("Bot '" + bot1Name + "' won " + botWins[1] + " games.", fileName);
		}

	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static void runGrandTournament(int numberOfGames) {
		// Use Supplier here to create a new instance when required in the parallel threads
		Supplier<HMCTSBot> IDE = () -> new HMCTSBot(true, new LeastDistanceToEnemySorting(), new ShortCircuitRandomBot());
		Supplier<HMCTSBot> IDEi = () -> new HMCTSBot(true, new InformedSorting(), new ShortCircuitRandomBot());
		Supplier<HMCTSBot> DE = () -> new HMCTSBot(false, new LeastDistanceToEnemySorting(), new ShortCircuitRandomBot());
		Supplier<LSIBot> LSI = () -> new LSIBot(new ShortCircuitRandomBot());
		Supplier<NMCBot> NMC = () -> new NMCBot(new ShortCircuitRandomBot());
		Supplier<HMCTSBot> IHE = () -> new HMCTSBot(true, new RandomSorting(), new ShortCircuitRandomBot());
		Supplier<HMCTSBot> HE = () -> new HMCTSBot(false, new RandomSorting(), new ShortCircuitRandomBot());
		Supplier<SquadBot> HeuristicBot = () -> new SquadBot();
		Supplier<ShortCircuitRandomBot> PlayoutBot = () -> new ShortCircuitRandomBot();

		Array<Supplier<? extends BaseBot>> tournamentBots = Array.with(IDE, IDEi, DE, LSI, NMC, IHE, HE, HeuristicBot, PlayoutBot);

		runAll1v1Combinations(numberOfGames, tournamentBots);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static void runPlayoutStrategies(int numberOfGames) {
		// Use Supplier here to create a new instance when required in the parallel threads
		Supplier<HMCTSBot> IHEr = () -> new HMCTSBot(true, new RandomSorting(), new ShortCircuitRandomBot());
		Supplier<HMCTSBot> IHEh = () -> new HMCTSBot(true, new RandomSorting(), new SquadBot());
		Supplier<NMCBot> NMCr = () -> new NMCBot(new ShortCircuitRandomBot());
		Supplier<NMCBot> NMCh = () -> new NMCBot(new SquadBot());
		Supplier<LSIBot> LSIr = () -> new LSIBot(new ShortCircuitRandomBot());
		Supplier<LSIBot> LSIh = () -> new LSIBot(new SquadBot());

		Array<Supplier<? extends BaseBot>> tournamentBots = Array.with(IHEr, IHEh, NMCr, NMCh, LSIr, LSIh);

		runAll1v1Combinations(numberOfGames, tournamentBots);
	}

	@SuppressWarnings("rawtypes")
	public static void runAll1v1Combinations(int numberOfGames, Array<Supplier<? extends BaseBot>> tournamentBots) {
		ICombinatoricsVector<Supplier<? extends BaseBot>> initialVector = Factory.createVector(tournamentBots.toArray());

		// Do a test for all combinations of 2 bots
		Generator<Supplier<? extends BaseBot>> generator = Factory.createSimpleCombinationGenerator(initialVector, 2);
		for (ICombinatoricsVector<Supplier<? extends BaseBot>> combination : generator) {
			Array<BaseBot> botsSetup = Array.with(combination.getValue(0)
																.get(), combination.getValue(1)
																					.get());

			final String bot0Name = botsSetup.get(0)
												.getBotName();
			final String bot1Name = botsSetup.get(1)
												.getBotName();

			// Create an array to store results
			int[] botWins = new int[] { 0, 0 };

			String fileName = bot0Name + " VS " + bot1Name;
			// Check if the filename already exists, if so, skip this combination
			if (fileExists(fileName))
				continue;

			// Write the combination to a file
			writeToFile("(" + numberOfGames + " games): " + combination, fileName);

			// This sets the system to use 2 cores
			System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "2");

			long totalTime = IntStream.range(0, numberOfGames)
										.parallel()
										.mapToLong(i -> {

											Array<BaseBot> bots = Array.with(combination.getValue(0)
																						.get(), combination.getValue(1)
																											.get());

											TournamentMatch game = new TournamentMatch(bots.get(0), bots.get(1));

											Stopwatch gameTimer = new Stopwatch();
											gameTimer.start();

											game.runGame(i);

											MatchData bot0Data = game.getBot0Data();
											writeToFile(bot0Data.toString(), fileName);
											if (bot0Data.botName.equals(bot0Name) && bot0Data.botRank == 0)
												botWins[0]++;
											MatchData bot1Data = game.getBot1Data();
											writeToFile(bot1Data.toString(), fileName);
											if (bot1Data.botName.equals(bot1Name) && bot1Data.botRank == 0)
												botWins[1]++;

											return gameTimer.end();
										})
										.sum();

			System.out.println("All games took " + TimeUnit.MILLISECONDS.convert(totalTime, TimeUnit.NANOSECONDS) + " miliseconds");
			writeToFile("Bot '" + bot0Name + "' won " + botWins[0] + " games.", fileName);
			writeToFile("Bot '" + bot1Name + "' won " + botWins[1] + " games.", fileName);
		}
	}

	@SuppressWarnings("rawtypes")
	public static void testC(int numberOfGames, double C) {
		Array<BaseBot> botsSetup = Array.with(	new HMCTSBot(true, new LeastDistanceToEnemySorting(), new ShortCircuitRandomBot(), C),
												new LSIBot(new ShortCircuitRandomBot()));

		final String bot0Name = botsSetup.get(0)
											.getBotName();
		final String bot1Name = botsSetup.get(1)
											.getBotName();

		// Create an array to store results
		int[] botWins = new int[] { 0, 0 };

		String fileName = bot0Name + " VS " + bot1Name + "_" + C;
		// Check if the filename already exists, if so, skip this test
		if (fileExists(fileName))
			return;

		// Write the combination to a file
		writeToFile("(" + numberOfGames + " games): C=" + C, fileName);

		// This sets the system to use 2 cores
		System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "2");

		long totalTime = IntStream.range(0, numberOfGames)
									.parallel()
									.mapToLong(i -> {

										Array<BaseBot> bots = Array.with(	new HMCTSBot(true, new LeastDistanceToEnemySorting(),
																							new ShortCircuitRandomBot(), C),
																			new LSIBot(new ShortCircuitRandomBot()));

										TournamentMatch game = new TournamentMatch(bots.get(0), bots.get(1));

										Stopwatch gameTimer = new Stopwatch();
										gameTimer.start();

										game.runGame(i);

										MatchData bot0Data = game.getBot0Data();
										writeToFile(bot0Data.toString(), fileName);
										if (bot0Data.botName.equals(bot0Name) && bot0Data.botRank == 0)
											botWins[0]++;
										MatchData bot1Data = game.getBot1Data();
										writeToFile(bot1Data.toString(), fileName);
										if (bot1Data.botName.equals(bot1Name) && bot1Data.botRank == 0)
											botWins[1]++;

										return gameTimer.end();
									})
									.sum();

		System.out.println("All games took " + TimeUnit.MILLISECONDS.convert(totalTime, TimeUnit.NANOSECONDS) + " miliseconds");
		writeToFile("Bot '" + bot0Name + "' won " + botWins[0] + " games.", fileName);
		writeToFile("Bot '" + bot1Name + "' won " + botWins[1] + " games.", fileName);
	}

	public static synchronized void writeToFile(String data, String filename) {
		try {
			BufferedWriter writer = new BufferedWriter(new FileWriter(BASE_PATH + filename + BASE_FILE_EXTENSION, true));
			writer.newLine();
			if (data != null) {
				writer.append(data);
			} else {
				writer.newLine();
			}
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static synchronized boolean fileExists(String filename) {
		File tmp = new File(BASE_PATH + filename + BASE_FILE_EXTENSION);
		return tmp.exists();
	}

}
