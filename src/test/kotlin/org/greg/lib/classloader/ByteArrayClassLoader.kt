package org.greg.lib.classloader

class ByteArrayClassLoader(private val classes: Map<String?, ByteArray>) :
    ClassLoader(ByteArrayClassLoader::class.java.classLoader) {
    @Throws(ClassNotFoundException::class)
    override fun findClass(name: String?): Class<*>? {
        val bytes = classes[name];
        if (bytes == null) {
            throw ClassNotFoundException()
        } else {
            return defineClass(name, classes[name], 0, classes[name]?.size ?: 0)
        }
    }
}
