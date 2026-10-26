// Shared test setup: under a full parallel run, a page can take longer than Testing Library's default
// 1 s to appear; waiting up to 3 s keeps the tests about behaviour, not machine load.
import { configure } from '@testing-library/react'

configure({ asyncUtilTimeout: 3000 })
