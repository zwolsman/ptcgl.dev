const BASE = import.meta.env.VITE_API_BASE_URL ?? '/api'

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`)
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return res.json() as Promise<T>
}

export interface SetSummary {
  id: string
  code: string
  series: string | null
  name: string | null
  releaseDate: string | null
  mainSetCount: number | null
  masterSetCount: number | null
  logo: string | null
}

export interface CardSummary {
  number: string
  name: string
  rarity: string | null
  image: string | null
  foilType: string | null
}

export function fetchSets(): Promise<SetSummary[]> {
  return get('/v1/sets')
}

export function fetchSetCards(setId: string): Promise<CardSummary[]> {
  return get(`/v1/sets/${setId}/cards`)
}
