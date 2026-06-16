import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { fetchSets, type SetSummary } from '../api/client'

export default function SetsPage() {
  const [sets, setSets] = useState<SetSummary[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchSets()
      .then(setSets)
      .catch((e: Error) => setError(e.message))
  }, [])

  if (error) return <p>Error: {error}</p>

  return (
    <main>
      <h1>Sets</h1>
      <ul>
        {sets.map((s) => (
          <li key={s.id}>
            <Link to={`/sets/${s.id}`}>
              {s.logo && <img src={s.logo} alt="" width={40} />}
              {s.name ?? s.id} ({s.code})
            </Link>
          </li>
        ))}
      </ul>
    </main>
  )
}
