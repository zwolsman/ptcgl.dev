import { useEffect, useState } from 'react'
import { useParams, Link } from 'react-router'
import { fetchSetCards, type CardSummary } from '../api/client'

export default function SetPage() {
  const { id } = useParams<{ id: string }>()
  const [cards, setCards] = useState<CardSummary[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    fetchSetCards(id)
      .then(setCards)
      .catch((e: Error) => setError(e.message))
  }, [id])

  if (error) return <p>Error: {error}</p>

  return (
    <main>
      <Link to="/">← Back</Link>
      <h1>Set {id}</h1>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        {cards.map((c) => (
          <div key={c.number} title={c.name}>
            {c.image
              ? <img src={c.image} alt={c.name} width={120} />
              : <span>{c.number} {c.name}</span>}
          </div>
        ))}
      </div>
    </main>
  )
}
