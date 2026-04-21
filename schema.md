# API Schemas

## 1. OpenSky Network (Primary)
- Endpoint: `GET https://opensky-network.org/api/states/all?lamin={lamin}&lomin={lomin}&lamax={lamax}&lomax={lomax}`
- Response: Returns a JSON object with a `states` array. Each state is an array of values.
- Relevant indices in the state array:
  - `[0]`: icao24 (String) - Transponder hex code.
  - `[1]`: callsign (String)
  - `[5]`: longitude (Float)
  - `[6]`: latitude (Float)
  - `[7]`: baro_altitude (Float) - in meters
  - `[9]`: velocity (Float) - in m/s
  - `[10]`: true_track (Float) - in degrees

## 2. HexDB (Secondary)
- Endpoint: `GET https://hexdb.io/api/v1/aircraft/{icao24}`
- Response JSON:

  {
    "ICAOTypeCode":"A319",
    "Manufacturer":"Airbus",
    "ModeS":"4010EE",
    "OperatorFlagCode":"EZY",
    "RegisteredOwners":"easyJet Airline",
    "Registration":"G-EZBZ",
    "Type":"A319 111"
  }
  ```json
  from which we are mostly just interested in "Type"
