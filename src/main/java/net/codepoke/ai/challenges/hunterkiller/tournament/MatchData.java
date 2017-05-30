package net.codepoke.ai.challenges.hunterkiller.tournament;

import lombok.AllArgsConstructor;
import lombok.Data;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.Json.Serializable;
import com.badlogic.gdx.utils.JsonValue;

/**
 * Holds the data on a single match for a bot.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
@Data
@AllArgsConstructor
public class MatchData
		implements Serializable {

	/**
	 * The name of the bot.
	 */
	public String botName;

	/**
	 * The rank attained by the bot in the match.
	 */
	public int botRank;

	/**
	 * The score attained by the bot in the match.
	 */
	public int botScore;

	/**
	 * The round number of the last round in the match.
	 */
	public int lastRound;

	@Override
	public void write(Json json) {
		json.writeArrayStart("botMatchData");
		json.writeValue(botName);
		json.writeValue(botRank);
		json.writeValue(botScore);
		json.writeValue(lastRound);
		json.writeArrayEnd();
	}

	@Override
	public void read(Json json, JsonValue jsonData) {
		JsonValue raw = jsonData.getChild("botMatchData");

		botName = raw.asString();
		botRank = (raw = raw.next).asInt();
		botScore = (raw = raw.next).asInt();
		lastRound = (raw = raw.next).asInt();
	}

}
