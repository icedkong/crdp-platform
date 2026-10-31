import { bootstrap } from '@fu/admin-shell'

bootstrap({
  mode: 'platform',
  views: import.meta.glob('./views/**/*.vue'),
})
