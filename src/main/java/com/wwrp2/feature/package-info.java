/**
 * Aquí vive cada funcionalidad del mod, una por paquete.
 *
 * <pre>
 * feature/
 * └── nombrefeature/      una feature = un paquete = una rama feature/nombre-feature
 *     ├── ...             lógica común (servidor y cliente)
 *     └── client/         solo cliente: render, HUD, teclas...
 * </pre>
 *
 * <p>Cada feature se registra sola con {@code @Mod.EventBusSubscriber}, así que normalmente
 * no hace falta tocar {@link com.wwrp2.Wwrp2}. Las normas completas están en
 * {@code CONTRIBUTING.md}, en la raíz del repo.</p>
 */
package com.wwrp2.feature;
