import { BrowserRouter, Routes, Route } from 'react-router'
import SetsPage from './routes/index'
import SetPage from './routes/sets.$id'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<SetsPage />} />
        <Route path="/sets/:id" element={<SetPage />} />
      </Routes>
    </BrowserRouter>
  )
}
