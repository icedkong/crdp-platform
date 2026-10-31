import { bootstrap } from '@crdp/admin-shell'

bootstrap({
  mode: 'platform',
  views: import.meta.glob('./views/**/*.vue'),
})
