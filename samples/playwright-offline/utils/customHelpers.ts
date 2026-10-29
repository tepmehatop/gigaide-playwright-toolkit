// untyped parameters on purpose: TypeScript knows nothing about `page`, the plugin falls back to Playwright's API by name
export async function selectFromDropDown(page, name, locator, custom = null) {
    await page.click(locator);
    await page.waitForTimeout(300);
}
