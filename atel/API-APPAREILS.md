# API appareils d'atel : ce que la TV lit

Contrat entre le serveur atel (NAS, ou le Mac pendant le développement) et ateltv. La TV ne fait que **lire** : aucun
appel ne modifie atel. Tant que `atel_server` est vide dans `config.properties`, la TV affiche des données d'exemple.

## Accès

- Adresse : `atel_server` dans `config.properties`, par exemple `http://192.168.1.20:4318` sur le réseau local
  (le `http://` n'est accepté que pour une adresse privée ou un nom `.local`/`.lan`, voir `lan_server` dans
  `build.sh`), sinon `https://…`.
- Jeton : propre à la TV, en lecture seule. Il n'est jamais dans l'APK ; on l'envoie une fois depuis l'ordinateur avec
  `python3 tools/send_key.py <IP de la TV> atel_token`. La TV l'envoie dans `Authorization: Bearer <jeton>`.
- Aucune redirection n'est suivie (le jeton partirait ailleurs). Réponse limitée à 512 Ko.
- La TV interroge toutes les 5 minutes tant que l'accueil est affiché, et à chaque retour sur l'accueil (au plus une
  fois par minute si le serveur ne répond pas). Elle garde la dernière réponse sur disque.

## `GET /device/v1/agenda?days=2`

Les rendez-vous d'aujourd'hui et de demain, et les rappels non faits.

```json
{
  "version": 1,
  "generated_at": "2026-11-25T11:00:00+01:00",
  "events": [
    {
      "id": "evt_8f2c",
      "title": "Dentiste",
      "start": "2026-11-26T09:00:00+01:00",
      "end": "2026-11-26T10:00:00+01:00",
      "all_day": false,
      "location": "Lyon 3e"
    },
    { "id": "evt_91aa", "title": "Anniversaire Léa", "start": "2026-11-25", "all_day": true }
  ],
  "reminders": [
    { "id": "rap_12", "title": "Attestation CAF", "due": "2026-11-26", "done": false }
  ]
}
```

- `start`, `end` : ISO 8601 avec décalage horaire (`+01:00` ou `Z`) ; pour une journée entière, une date seule
  (`2026-11-25`), comprise comme minuit à l'heure de la TV. `end` est facultatif.
- `due` (rappels) : une date seule, ou une date et heure ISO 8601.
- `location` facultatif. Les champs inconnus sont ignorés, un élément illisible est sauté sans masquer les autres.
- `done: true` : le rappel n'est pas affiché (le serveur peut aussi ne pas l'envoyer).
- Mode « Occupé » (prévu côté serveur) : le serveur remplace lui-même les titres par « Occupé » ; la TV n'a rien à
  faire.

Codes : `200` réponse ci-dessus ; `401`/`403` jeton refusé (la TV le note dans son journal, sans le jeton) ; tout autre
code est traité comme « serveur indisponible » et la carte garde la dernière réponse.

## Ce que la TV en affiche

La carte « Aujourd'hui », à droite de la météo, montre au plus 3 lignes :

1. les rendez-vous d'aujourd'hui pas encore finis (« dans 1 h 50 », « en cours ») ;
2. puis ceux de demain (« demain ») ;
3. puis les rappels, les plus proches d'abord (« demain », « en retard », « 15 déc. »).

## Prochains appels prévus

Pour les étapes suivantes d'ateltv (rappel par-dessus les applis, écran de veille), la même réponse suffit. Plus tard,
pour l'appairage sans copier de jeton : `POST /device/v1/pair/start` et `GET /device/v1/pair/poll` (la TV affiche un
code, on le valide dans atel › Paramètres › Appareils), décrits dans le cadrage du serveur partagé.
