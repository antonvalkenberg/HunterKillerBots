package net.codepoke.ai.challenges.hunterkiller;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
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
		runDimensionalExpansion(100, false);
		// runDimensionalExpansion(100, true);
		// runPlayoutStrategies(100);
		// runGrandTournament(100);
		// runNoFogOfWar(200);
	}

	public static void runDimensionalExpansion(int numberOfGames, boolean useSideInformation) {
		// Define the sorting strategies we want to have in the rotation
		RandomSorting randomSort = new RandomSorting();
		StaticSorting staticSort = new StaticSorting();
		LeastDistanceToEnemySorting enemySort = new LeastDistanceToEnemySorting();
		AttackSorting attackSort = new AttackSorting();
		InformedSorting informedSort = new InformedSorting();

		Array<ControlledObjectSortingStrategy> sortingStrats = Array.with(randomSort, staticSort, enemySort, attackSort);
		if (useSideInformation)
			sortingStrats.add(informedSort);

		// Create a combinatorics vector based on the strategy array
		ICombinatoricsVector<ControlledObjectSortingStrategy> initialVector = Factory.createVector(sortingStrats.toArray());

		// Do a test for all combinations of 2 strategies
		Generator<ControlledObjectSortingStrategy> generator = Factory.createSimpleCombinationGenerator(initialVector, 2);
		for (ICombinatoricsVector<ControlledObjectSortingStrategy> combination : generator) {

			@SuppressWarnings("rawtypes")
			Array<BaseBot> botsSetup = Array.with(	new HMCTSBot(useSideInformation, combination.getValue(0), new ShortCircuitRandomBot()),
													new HMCTSBot(useSideInformation, combination.getValue(1), new ShortCircuitRandomBot()));

			final String bot0Name = botsSetup.get(0)
												.getBotName();
			final String bot1Name = botsSetup.get(1)
												.getBotName();

			// Create an array to store results
			int[] botWins = new int[] { 0, 0 };

			String fileName = bot0Name + " VS " + bot1Name;

			// Write the combination to a file
			writeToFile("(" + numberOfGames + " games): " + combination, fileName);

			// This sets the system to use 2 cores
			System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "1");

			long totalTime = IntStream.range(0, numberOfGames)
										.parallel()
										.mapToLong(i -> {

											@SuppressWarnings("rawtypes")
											Array<BaseBot> bots = Array.with(	new HMCTSBot(useSideInformation, combination.getValue(0),
																								new ShortCircuitRandomBot()),
																				new HMCTSBot(useSideInformation, combination.getValue(1),
																								new ShortCircuitRandomBot()));

											TournamentMatch game = new TournamentMatch(bots.get(0), bots.get(1));

											Stopwatch gameTimer = new Stopwatch();
											gameTimer.start();

											game.runGame(i);

											MatchData bot0Data = game.getBot0Data();
											if (bot0Data.botName.equals(bot0Name) && bot0Data.botRank == 0)
												botWins[0]++;
											MatchData bot1Data = game.getBot1Data();
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
	public static void runGrandTournament(int numberOfGames) {
		// Define the bots we want in the rotation
		HMCTSBot IDE = new HMCTSBot(true, new LeastDistanceToEnemySorting(), new ShortCircuitRandomBot());
		HMCTSBot DE = new HMCTSBot(false, new LeastDistanceToEnemySorting(), new ShortCircuitRandomBot());
		LSIBot LSI = new LSIBot(new ShortCircuitRandomBot());
		NMCBot NMC = new NMCBot(new ShortCircuitRandomBot());
		HMCTSBot IHE = new HMCTSBot(true, new RandomSorting(), new ShortCircuitRandomBot());
		HMCTSBot HE = new HMCTSBot(false, new RandomSorting(), new ShortCircuitRandomBot());
		SquadBot HeuristicBot = new SquadBot();
		ShortCircuitRandomBot PlayoutBot = new ShortCircuitRandomBot();

		Array<BaseBot> tournamentBots = Array.with(IDE, DE, LSI, NMC, IHE, HE, HeuristicBot, PlayoutBot);

		runAll1v1Combinations(numberOfGames, tournamentBots);
	}

	@SuppressWarnings("rawtypes")
	public static void runPlayoutStrategies(int numberOfGames) {
		// Define the bots we want in the rotation
		HMCTSBot IHEr = new HMCTSBot(true, new RandomSorting(), new ShortCircuitRandomBot());
		HMCTSBot IHEh = new HMCTSBot(true, new RandomSorting(), new SquadBot());
		NMCBot NMCr = new NMCBot(new ShortCircuitRandomBot());
		NMCBot NMCh = new NMCBot(new SquadBot());
		LSIBot LSIr = new LSIBot(new ShortCircuitRandomBot());
		LSIBot LSIh = new LSIBot(new SquadBot());

		Array<BaseBot> tournamentBots = Array.with(IHEr, IHEh, NMCr, NMCh, LSIr, LSIh);

		runAll1v1Combinations(numberOfGames, tournamentBots);
	}

	@SuppressWarnings("rawtypes")
	public static void runAll1v1Combinations(int numberOfGames, Array<BaseBot> tournamentBots) {
		ICombinatoricsVector<BaseBot> initialVector = Factory.createVector(tournamentBots.toArray());

		// Do a test for all combinations of 2 bots
		Generator<BaseBot> generator = Factory.createSimpleCombinationGenerator(initialVector, 2);
		for (ICombinatoricsVector<BaseBot> combination : generator) {
			Array<BaseBot> botsSetup = Array.with(combination.getValue(0), combination.getValue(1));

			final String bot0Name = botsSetup.get(0)
												.getBotName();
			final String bot1Name = botsSetup.get(1)
												.getBotName();

			// Create an array to store results
			int[] botWins = new int[] { 0, 0 };

			String fileName = bot0Name + " VS " + bot1Name;

			// Write the combination to a file
			writeToFile("(" + numberOfGames + " games): " + combination, fileName);

			// This sets the system to use 2 cores
			System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "1");

			long totalTime = IntStream.range(0, numberOfGames)
										.parallel()
										.mapToLong(i -> {

											Array<BaseBot> bots = Array.with(combination.getValue(0), combination.getValue(1));

											TournamentMatch game = new TournamentMatch(bots.get(0), bots.get(1));

											Stopwatch gameTimer = new Stopwatch();
											gameTimer.start();

											game.runGame(i);

											MatchData bot0Data = game.getBot0Data();
											if (bot0Data.botName.equals(bot0Name) && bot0Data.botRank == 0)
												botWins[0]++;
											MatchData bot1Data = game.getBot1Data();
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

	public static void writeMatchDataToFile(MatchData data, String filename) {
		writeToFile(data.botName + "\t" + data.botRank + "\t" + data.botScore + "\t" + data.lastRound, filename);
	}

	public static void writeToFile(String data, String filename) {
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

}
