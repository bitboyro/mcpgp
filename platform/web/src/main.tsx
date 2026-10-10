import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { App } from './App'
import { ApiError } from './api'
import './styles.css'

const queryClient = new QueryClient({
  defaultOptions: {
    // retry what may be passing (the platform restarting), never refusals
    queries: { retry: (count, error) => count < 1 && !(error instanceof ApiError && error.status < 500), staleTime: 5_000 },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
