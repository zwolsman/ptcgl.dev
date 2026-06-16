FROM nginx:alpine
COPY docker/assets.nginx.conf.template /etc/nginx/templates/default.conf.template
