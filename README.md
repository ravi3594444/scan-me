# scan-me

A simple weather dashboard in plain HTML, CSS and JavaScript. It uses the free
[Open-Meteo](https://open-meteo.com/) API, so it needs no API key.

## Features

- Search any city, or use your current location
- Current conditions: temperature, feels-like, humidity, wind, precipitation
- Hourly forecast for the next 24 hours
- 7-day forecast with a temperature range bar for each day
- °C / °F toggle; your last city and unit are remembered
- Light and dark mode follow your system setting

## Run it

There's no build step. Open `index.html` in a browser, or serve the folder:

```sh
python3 -m http.server 8000
# then visit http://localhost:8000
```

Geolocation only works over `https://` or `localhost`.
