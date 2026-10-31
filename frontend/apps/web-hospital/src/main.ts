import { bootstrap } from '@crdp/admin-shell'

bootstrap({
  mode: 'hospital',
  views: import.meta.glob('./views/**/*.vue'),
})
