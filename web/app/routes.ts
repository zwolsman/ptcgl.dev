import { type RouteConfig, index, route } from "@react-router/dev/routes";

export default [
  index("routes/home.tsx"),
  route("search", "routes/search.tsx"),
  route("sets/:id", "routes/sets.$id.tsx"),
  route("cards/:id", "routes/cards.$id.tsx"),
  route("_locale", "routes/api.locale.ts"),
] satisfies RouteConfig;
