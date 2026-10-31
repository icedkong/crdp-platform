import { bootstrap } from '@fu/admin-shell'

bootstrap({
  mode: 'hospital',
  views: import.meta.glob('./views/**/*.vue'),
})
