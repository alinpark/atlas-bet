package bet;

import java.util.List;

/** Catalogue explicite : tous les marchés football sont à 90 minutes, arrêts de jeu inclus. */
final class Competitions {
    record Competition(String id, String name, int newsId, boolean cup) {}
    static final List<Competition> ALL = List.of(
        new Competition("soccer_france_ligue_one", "Ligue 1", 61, false),
        new Competition("soccer_epl", "Premier League", 39, false),
        new Competition("soccer_germany_bundesliga", "Bundesliga", 78, false),
        new Competition("soccer_spain_la_liga", "La Liga", 140, false),
        new Competition("soccer_uefa_champs_league", "Champions League", 2, true),
        new Competition("soccer_uefa_europa_league", "Europa League", 3, true));
    static String name(String id) { return ALL.stream().filter(c->c.id().equals(id)).map(Competition::name).findFirst().orElse(id); }
    static boolean cup(String id) { return ALL.stream().anyMatch(c->c.id().equals(id) && c.cup()); }
}
