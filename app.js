const GEO_URL = "https://geocoding-api.open-meteo.com/v1/search";
const FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
const STORAGE_KEY = "weather-dashboard";

// WMO weather codes -> [description, day icon, night icon]
const WEATHER_CODES = {
  0: ["Clear sky", "☀️", "🌙"],
  1: ["Mainly clear", "🌤️", "🌙"],
  2: ["Partly cloudy", "⛅", "☁️"],
  3: ["Overcast", "☁️", "☁️"],
  45: ["Fog", "🌫️", "🌫️"],
  48: ["Rime fog", "🌫️", "🌫️"],
  51: ["Light drizzle", "🌦️", "🌧️"],
  53: ["Drizzle", "🌦️", "🌧️"],
  55: ["Heavy drizzle", "🌧️", "🌧️"],
  56: ["Freezing drizzle", "🌧️", "🌧️"],
  57: ["Freezing drizzle", "🌧️", "🌧️"],
  61: ["Light rain", "🌦️", "🌧️"],
  63: ["Rain", "🌧️", "🌧️"],
  65: ["Heavy rain", "🌧️", "🌧️"],
  66: ["Freezing rain", "🌧️", "🌧️"],
  67: ["Freezing rain", "🌧️", "🌧️"],
  71: ["Light snow", "🌨️", "🌨️"],
  73: ["Snow", "🌨️", "🌨️"],
  75: ["Heavy snow", "❄️", "❄️"],
  77: ["Snow grains", "🌨️", "🌨️"],
  80: ["Rain showers", "🌦️", "🌧️"],
  81: ["Rain showers", "🌧️", "🌧️"],
  82: ["Violent showers", "⛈️", "⛈️"],
  85: ["Snow showers", "🌨️", "🌨️"],
  86: ["Snow showers", "❄️", "❄️"],
  95: ["Thunderstorm", "⛈️", "⛈️"],
  96: ["Thunderstorm, hail", "⛈️", "⛈️"],
  99: ["Thunderstorm, hail", "⛈️", "⛈️"],
};

const $ = (id) => document.getElementById(id);

const state = loadState();

function loadState() {
  const fallback = {
    unit: "celsius",
    place: { name: "London", label: "London, United Kingdom", latitude: 51.5085, longitude: -0.1257 },
  };
  try {
    return { ...fallback, ...JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}") };
  } catch {
    return fallback;
  }
}

function saveState() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // Storage unavailable (private mode etc.) — not critical.
  }
}

function describe(code, isDay = 1) {
  const [text, day, night] = WEATHER_CODES[code] || ["Unknown", "❔", "❔"];
  return { text, icon: isDay ? day : night };
}

function setStatus(message, isError = false) {
  const el = $("status");
  el.textContent = message;
  el.classList.toggle("error", isError);
}

async function fetchJSON(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Request failed (${res.status})`);
  return res.json();
}

async function geocode(query) {
  const url = `${GEO_URL}?name=${encodeURIComponent(query)}&count=1&language=en&format=json`;
  const data = await fetchJSON(url);
  const r = data.results?.[0];
  if (!r) throw new Error(`No results for "${query}"`);
  const label = [r.name, r.admin1, r.country].filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).join(", ");
  return { name: r.name, label, latitude: r.latitude, longitude: r.longitude };
}

async function loadWeather() {
  const { place, unit } = state;
  setStatus("Loading…");
  const params = new URLSearchParams({
    latitude: place.latitude,
    longitude: place.longitude,
    current: "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,is_day",
    hourly: "temperature_2m,weather_code,precipitation_probability,is_day",
    daily: "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
    temperature_unit: unit,
    wind_speed_unit: unit === "fahrenheit" ? "mph" : "kmh",
    precipitation_unit: unit === "fahrenheit" ? "inch" : "mm",
    timezone: "auto",
    forecast_days: "7",
  });
  try {
    const data = await fetchJSON(`${FORECAST_URL}?${params}`);
    render(data);
    setStatus("");
  } catch (err) {
    setStatus(`Couldn't load weather: ${err.message}`, true);
  }
}

function render(data) {
  const { current, current_units: cu, hourly, daily } = data;
  const deg = "°";

  // Current conditions
  const now = describe(current.weather_code, current.is_day);
  $("place").textContent = state.place.label;
  $("updated").textContent = `Updated ${formatTime(current.time)} local time`;
  $("current-icon").textContent = now.icon;
  $("current-temp").textContent = `${Math.round(current.temperature_2m)}${deg}`;
  $("current-desc").textContent = now.text;
  $("feels").textContent = `${Math.round(current.apparent_temperature)}${deg}`;
  $("humidity").textContent = `${current.relative_humidity_2m}%`;
  $("wind").textContent = `${Math.round(current.wind_speed_10m)} ${cu.wind_speed_10m}`;
  $("precip").textContent = `${current.precipitation} ${cu.precipitation}`;

  // Hourly: next 24 hours starting from the current hour
  const startIdx = Math.max(0, hourly.time.findIndex((t) => t >= current.time.slice(0, 13)));
  const hourlyEl = $("hourly");
  hourlyEl.replaceChildren();
  for (let i = startIdx; i < Math.min(startIdx + 24, hourly.time.length); i++) {
    const w = describe(hourly.weather_code[i], hourly.is_day[i]);
    const div = document.createElement("div");
    div.className = "hour";
    div.title = w.text;
    div.innerHTML = `
      <div>${i === startIdx ? "Now" : formatHour(hourly.time[i])}</div>
      <span class="icon">${w.icon}</span>
      <div><strong>${Math.round(hourly.temperature_2m[i])}${deg}</strong></div>
      <div class="pop">${hourly.precipitation_probability[i] ?? 0}%</div>`;
    hourlyEl.append(div);
  }

  // Daily with a temperature range bar scaled across the week
  const weekMin = Math.min(...daily.temperature_2m_min);
  const weekMax = Math.max(...daily.temperature_2m_max);
  const span = weekMax - weekMin || 1;
  const dailyEl = $("daily");
  dailyEl.replaceChildren();
  daily.time.forEach((day, i) => {
    const w = describe(daily.weather_code[i]);
    const lo = daily.temperature_2m_min[i];
    const hi = daily.temperature_2m_max[i];
    const li = document.createElement("li");
    li.className = "day";
    li.title = w.text;
    li.innerHTML = `
      <span>${i === 0 ? "Today" : formatDay(day)}</span>
      <span class="icon">${w.icon}</span>
      <span class="lo">${Math.round(lo)}${deg}</span>
      <div class="range"><span style="left:${((lo - weekMin) / span) * 100}%;right:${((weekMax - hi) / span) * 100}%"></span></div>
      <span class="hi">${Math.round(hi)}${deg}</span>`;
    dailyEl.append(li);
  });

  ["current", "hourly-card", "daily-card"].forEach((id) => ($(id).hidden = false));
}

// API times are local ISO strings like "2026-09-26T14:00" — parse without timezone shifting.
function formatTime(iso) {
  return iso.slice(11, 16);
}

function formatHour(iso) {
  const h = Number(iso.slice(11, 13));
  return `${h % 12 || 12}${h < 12 ? "am" : "pm"}`;
}

function formatDay(isoDate) {
  const [y, m, d] = isoDate.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { weekday: "short" });
}

// Event wiring
$("search-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const query = $("search-input").value.trim();
  if (!query) return;
  setStatus("Searching…");
  try {
    state.place = await geocode(query);
    saveState();
    $("search-input").value = "";
    await loadWeather();
  } catch (err) {
    setStatus(err.message, true);
  }
});

$("locate-btn").addEventListener("click", () => {
  if (!navigator.geolocation) {
    setStatus("Geolocation isn't supported by your browser.", true);
    return;
  }
  setStatus("Finding your location…");
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      const { latitude, longitude } = pos.coords;
      state.place = {
        name: "My location",
        label: `My location (${latitude.toFixed(2)}, ${longitude.toFixed(2)})`,
        latitude,
        longitude,
      };
      saveState();
      loadWeather();
    },
    (err) => setStatus(`Couldn't get location: ${err.message}`, true)
  );
});

document.querySelectorAll(".units button").forEach((btn) => {
  btn.classList.toggle("active", btn.dataset.unit === state.unit);
  btn.addEventListener("click", () => {
    if (btn.dataset.unit === state.unit) return;
    state.unit = btn.dataset.unit;
    document.querySelectorAll(".units button").forEach((b) => b.classList.toggle("active", b === btn));
    saveState();
    loadWeather();
  });
});

loadWeather();
