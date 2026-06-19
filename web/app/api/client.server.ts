export interface Series {
  id: string;
  setCount: number;
}

export interface SeriesDetail {
  id: string;
  sets: Set[];
}

export interface Set {
  id: string;
  series: string | null;
  code: string;
  name: string | null;
  releaseDate: string | null;
  mainSetCount: number | null;
  masterSetCount: number | null;
  logo: string | null;
}

export interface CardSummary {
  id: string;
  number: string;
  position: string | null;
  name: string | null;
  thumb: string | null;
}

export interface Card {
  id: string;
  setId: string;
  series: string | null;
  number: string;
  position: string | null;
  name: string | null;
  category: string | null;
  rarity: string | null;
  regulationMark: string | null;
  hp: number | null;
  types: string[];
  evolvesFrom: string | null;
  text: string | null;
  retreat: number | null;
  weakness: { type: string; amount: string } | null;
  resistance: { type: string; amount: string } | null;
  variants: { id: string; thumb: string | null; type: string }[];
  otherPrints: { id: string; thumb: string | null }[];
  attacks: Attack[];
  assets: CardAssets;
}

export interface Attack {
  slot: number;
  name: string | null;
  cost: string | null;
  damage: string | null;
  text: string | null;
}

export interface CardAssets {
  hires: string | null;
  thumb: string | null;
  whiteplate: string | null;
  etch: string | null;
  foilType: string | null;
}

function apiBase(): string {
  return process.env.API_BASE_URL ?? "http://localhost:8080"
}

async function apiFetch(path: string): Promise<Response> {
  const res = await fetch(`${apiBase()}${path}`);
  if (!res.ok) throw new Response(res.statusText, { status: res.status });
  return res;
}

export async function fetchSeries(): Promise<Series[]> {
  return (await apiFetch("/v1/series")).json();
}

export async function fetchSeriesDetail(series: string, locale = "en"): Promise<SeriesDetail> {
  return (await apiFetch(`/v1/series/${series}?locale=${locale}`)).json();
}

export async function fetchSets(locale = "en"): Promise<Set[]> {
  return (await apiFetch(`/v1/sets?locale=${locale}`)).json();
}

export async function fetchSet(id: string, locale = "en"): Promise<Set> {
  return (await apiFetch(`/v1/sets/${id}?locale=${locale}`)).json();
}

export async function fetchSetCards(setId: string, locale = "en"): Promise<CardSummary[]> {
  return (await apiFetch(`/v1/sets/${setId}/cards?locale=${locale}`)).json();
}

export async function fetchCard(id: string, locale = "en"): Promise<Card> {
  return (await apiFetch(`/v1/cards/${id}?locale=${locale}`)).json();
}

export async function searchCards(name: string, locale = "en", exact = false): Promise<CardSummary[]> {
  const params = new URLSearchParams({ name, locale, ...(exact ? { exact: "true" } : {}) });
  return (await apiFetch(`/v1/cards?${params}`)).json();
}
